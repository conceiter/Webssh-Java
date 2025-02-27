package cn.objectspace.webssh.service.impl;

import cn.objectspace.webssh.constant.ConstantPool;
import cn.objectspace.webssh.pojo.SSHConnectInfo;
import cn.objectspace.webssh.pojo.WebSSHData;
import cn.objectspace.webssh.service.WebSSHService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Description: WebSSH业务逻辑实现
 * @Author: NoCortY
 * @Date: 2020/3/8
 */
@Service
public class WebSSHServiceImpl implements WebSSHService {
    //存放ssh连接信息的map
    private static Map<String, Object> sshMap = new ConcurrentHashMap<>();

    private Logger logger = LoggerFactory.getLogger(WebSSHServiceImpl.class);
    //线程池
    private ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * @Description: 初始化连接
     * @Param: [session]
     * @return: void
     * @Author: NoCortY
     * @Date: 2020/3/7
     */
    @Override
    public void initConnection(WebSocketSession session) {
        JSch jSch = new JSch();
        SSHConnectInfo sshConnectInfo = new SSHConnectInfo();
        sshConnectInfo.setjSch(jSch);
        sshConnectInfo.setWebSocketSession(session);
        String uuid = String.valueOf(session.getAttributes().get(ConstantPool.USER_UUID_KEY));
        //将这个ssh连接信息放入map中
        sshMap.put(uuid, sshConnectInfo);
    }

    /**
     * @Description: 处理客户端发送的数据
     * @Param: [buffer, session]
     * @return: void
     * @Author: NoCortY
     * @Date: 2020/3/7
     */
    @Override
    public void recvHandle(String buffer, WebSocketSession session) {
        ObjectMapper objectMapper = new ObjectMapper();
        WebSSHData webSSHData = null;
        try {
            webSSHData = objectMapper.readValue(buffer, WebSSHData.class);
        } catch (IOException e) {
            logger.error("Json转换异常");
            logger.error("异常信息:{}", e.getMessage());
            return;
        }
        String userId = String.valueOf(session.getAttributes().get(ConstantPool.USER_UUID_KEY));
        if (ConstantPool.WEBSSH_OPERATE_CONNECT.equals(webSSHData.getOperate())) {
            //找到刚才存储的ssh连接对象
            SSHConnectInfo sshConnectInfo = (SSHConnectInfo) sshMap.get(userId);
            //启动线程异步处理
            WebSSHData finalWebSSHData = webSSHData;
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        connectToSSH(sshConnectInfo, finalWebSSHData, session);
                        if (sshConnectInfo.getChannel().isClosed()) {
                            close(session);
                        }
                    } catch (JSchException | IOException e) {
                        logger.error("webssh连接异常");
                        logger.error("异常信息:{}", e.getMessage());
                        try {
                            //发送错误信息
                            sendMessage(session, ("ERROR : " + e.getMessage()).getBytes());
                        } catch (IOException ex) {
                            logger.error("消息发送失败");
                            logger.error("异常信息:{}", ex.getMessage());
                        }
                        close(session);
                    }
                }
            });
        } else if (ConstantPool.WEBSSH_OPERATE_COMMAND.equals(webSSHData.getOperate())) {
            String command = webSSHData.getCommand();
            if (null == command || "".equals(command)) {
                return;
            }
            SSHConnectInfo sshConnectInfo = (SSHConnectInfo) sshMap.get(userId);
            if (sshConnectInfo != null) {
                try {
                    ChannelShell channel = (ChannelShell) sshConnectInfo.getChannel();
                    if (channel != null) {
                        channel.setPtySize(webSSHData.getCols(), webSSHData.getRows(), webSSHData.getWidth(), webSSHData.getHeight());
                        transToSSH(channel, command);
                        if (channel.isClosed()) {
                            close(session);
                        }
                    }
                } catch (IOException e) {
                    logger.error("webssh连接异常");
                    logger.error("异常信息:{}", e.getMessage());
                    try {
                        //发送错误信息
                        sendMessage(session, ("ERROR : " + e.getMessage()).getBytes());
                    } catch (IOException ex) {
                        logger.error("消息发送失败");
                        logger.error("异常信息:{}", ex.getMessage());
                    }
                    close(session);
                }
            }
        } else if (ConstantPool.WEBSSH_OPERATE_HEARTBEAT.equals(webSSHData.getOperate())) {
            //检查心跳
            SSHConnectInfo sshConnectInfo = (SSHConnectInfo) sshMap.get(userId);
            if (sshConnectInfo != null) {
                try {
                    //处于连接状态则发送健康数据，不能为空，空则断开连接。
                    if (sshConnectInfo.getChannel().isConnected())
                        sendMessage(session, "Heartbeat healthy".getBytes());
                } catch (IOException e) {
                    logger.error("消息发送失败");
                    logger.error("异常信息:{}", e.getMessage());
                }
            }
        } else {
            logger.error("不支持的操作");
            close(session);
        }
    }

    @Override
    public void sendMessage(WebSocketSession session, byte[] buffer) throws IOException {
        session.sendMessage(new TextMessage(buffer));
    }

    @Override
    public void close(WebSocketSession session) {
        String userId = String.valueOf(session.getAttributes().get(ConstantPool.USER_UUID_KEY));
        SSHConnectInfo sshConnectInfo = (SSHConnectInfo) sshMap.get(userId);
        if (sshConnectInfo != null) {
            //断开连接
            if (sshConnectInfo.getChannel() != null) sshConnectInfo.getChannel().disconnect();
            //map中移除
            sshMap.remove(userId);
        }
        try {
            session.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @Description: 使用jsch连接终端
     * @Param: [cloudSSH, webSSHData, webSocketSession]
     * @return: void
     * @Author: NoCortY
     * @Date: 2020/3/7
     */
    private void connectToSSH(SSHConnectInfo sshConnectInfo, WebSSHData webSSHData, WebSocketSession webSocketSession) throws JSchException, IOException {
        Session session = null;
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        //获取jsch的会话
        session = sshConnectInfo.getjSch().getSession(webSSHData.getUsername(), webSSHData.getHost(), webSSHData.getPort());
        session.setConfig(config);
        //设置密码
        session.setPassword(webSSHData.getPassword());
        //连接  超时时间30s
        session.connect(30000);

        //开启shell通道
        Channel channels = session.openChannel("shell");
        ChannelShell channel = (ChannelShell) channels;
        channel.setPtySize(webSSHData.getCols(), webSSHData.getRows(), webSSHData.getWidth(), webSSHData.getHeight());

        //通道连接 超时时间3s
        channel.connect(3000);

        //设置channel
        sshConnectInfo.setChannel(channel);

        //转发消息
        transToSSH(channel, "\r");

        //读取终端返回的信息流
        InputStream inputStream = channel.getInputStream();
        try {
            //循环读取
            byte[] buffer = new byte[1024];
            int i = 0;
            //如果没有数据来，线程会一直阻塞在这个地方等待数据。
            while ((i = inputStream.read(buffer)) != -1) {
                sendMessage(webSocketSession, Arrays.copyOfRange(buffer, 0, i));
            }

        } finally {
            //断开连接后关闭会话
            session.disconnect();
            channel.disconnect();
            if (inputStream != null) {
                inputStream.close();
            }
        }

    }

    /**
     * @Description: 将消息转发到终端
     * @Param: [channel, data]
     * @return: void
     * @Author: NoCortY
     * @Date: 2020/3/7
     */
    private void transToSSH(Channel channel, String command) throws IOException {
        if (channel != null) {
            OutputStream outputStream = channel.getOutputStream();
            outputStream.write(command.getBytes());
            outputStream.flush();
        }
    }
}
