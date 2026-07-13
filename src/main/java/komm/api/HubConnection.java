package komm.api;

import komm.api.auth.HubAuth;
import komm.api.auth.TokenManager;
import komm.service.DirectMessageService;
import komm.service.FriendService;
import komm.service.GifService;
import komm.service.HubModerationService;
import komm.service.HubPermissionService;
import komm.service.InstallationService;
import komm.service.InviteService;
import komm.service.ServerService;
import komm.service.UserService;
import lombok.Getter;

@Getter
public class HubConnection {

    private final HttpClientWrapper httpClient;
    private final TokenManager tokenManager;
    private final HubAuth auth;

    private final GifService gifService;
    private final UserService userService;
    private final ServerService serverService;
    private final InstallationService installationService;
    private final FriendService friendService;
    private final HubPermissionService hubPermissionService;
    private final HubModerationService hubModerationService;
    private final DirectMessageService directMessageService;
    private final InviteService inviteService;

    public HubConnection(String baseUrl) {
        this.httpClient = new HttpClientWrapper(baseUrl);
        this.tokenManager = new TokenManager(httpClient);
        this.auth = new HubAuth(httpClient, tokenManager);

        this.gifService = new GifService(httpClient, tokenManager);
        this.userService = new UserService(httpClient, tokenManager);
        this.serverService = new ServerService(httpClient, tokenManager);
        this.installationService = new InstallationService(httpClient, tokenManager);
        this.friendService = new FriendService(httpClient, tokenManager);
        this.hubPermissionService = new HubPermissionService(httpClient, tokenManager);
        this.hubModerationService = new HubModerationService(httpClient, tokenManager);
        this.directMessageService = new DirectMessageService(httpClient, tokenManager);
        this.inviteService = new InviteService(httpClient, tokenManager);
    }

    public void login(String username, String password) throws Exception {
        auth.login(username, password);
    }

    public void verifyEmail(String email, String code) throws Exception {
        auth.verifyEmail(email, code);
    }

    public void resendVerification(String email) throws Exception {
        auth.resendVerification(email);
    }

    public void logout() {
        auth.logout();
    }
}