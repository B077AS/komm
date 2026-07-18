package komm.api;

import komm.api.auth.InstallationAuth;
import komm.api.auth.TokenManager;
import komm.model.dto.summary.ServerSummary;
import komm.service.ChannelPermissionService;
import komm.service.ChannelService;
import komm.service.InstallationPermissionService;
import komm.service.MemberService;
import komm.service.MessageService;
import komm.service.SoundboardService;
import komm.websocket.InstallationWsClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class InstallationConnection {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;
    private final InstallationAuth auth;
    private final InstallationWsClient wsClient;
    private final ChannelService channelService;
    private final MessageService messageService;
    private final ChannelPermissionService channelPermissionService;
    private final InstallationPermissionService installationPermissionService;
    private final SoundboardService soundboardService;
    private final MemberService memberService;

    public InstallationConnection(ServerSummary server, String ticket) throws Exception {
        // The hub reports whether this installation serves TLS with its hub-signed
        // certificate. If so, connect via https/wss and validate against the hub CA;
        // legacy installations stay on plain http/ws.
        boolean tls = server.isTlsEnabled();
        javax.net.ssl.SSLContext sslContext =
                tls ? komm.api.tls.HubCaTrust.contextFor(server.getInstallationId()) : null;

        String hostPort = server.getIpAddress() + ":" + server.getPort();
        String baseUrl = (tls ? "https://" : "http://") + hostPort;
        log.debug("Connecting to installation at {} (hub reports tlsEnabled={})", baseUrl, tls);

        this.httpClient = new HttpClientWrapper(baseUrl, sslContext);
        this.tokenManager = new TokenManager(httpClient);
        this.auth = new InstallationAuth(httpClient, tokenManager);

        auth.login(ticket);

        String wsBase = (tls ? "wss://" : "ws://") + hostPort;
        this.wsClient = new InstallationWsClient(wsBase, sslContext);
        this.wsClient.connect(tokenManager.getAccessToken());

        this.channelService = new ChannelService(httpClient, tokenManager);
        this.messageService = new MessageService(httpClient, tokenManager);
        this.channelPermissionService = new ChannelPermissionService(httpClient, tokenManager);
        this.installationPermissionService = new InstallationPermissionService(httpClient, tokenManager);
        this.soundboardService = new SoundboardService(httpClient, tokenManager);
        this.memberService = new MemberService(httpClient, tokenManager);
    }

    public void close() {
        wsClient.disconnect();
        tokenManager.clearTokens();
        log.debug("Installation connection closed");
    }
}