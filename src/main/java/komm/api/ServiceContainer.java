package komm.api;

import komm.utils.AppConfig;
import lombok.Getter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Getter
public class ServiceContainer {

    private static volatile ServiceContainer instance;

    private final HubConnection hub;
    private volatile InstallationConnection installation;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private ServiceContainer() {
        this.hub = new HubConnection(AppConfig.getInstance().getApiUrl());
    }

    public static ServiceContainer getInstance() {
        if (instance == null) {
            synchronized (ServiceContainer.class) {
                if (instance == null) {
                    instance = new ServiceContainer();
                }
            }
        }
        return instance;
    }

    public HubConnection hub() {
        return hub;
    }

    public InstallationConnection installation() {
        if (installation == null) {
            throw new IllegalStateException("Not connected to any installation");
        }
        return installation;
    }

    public boolean hasInstallation() {
        return installation != null;
    }

    public synchronized void setInstallation(InstallationConnection installation) {
        if (this.installation != null) {
            this.installation.close();
        }
        this.installation = installation;
    }

    public synchronized void disconnectInstallation() {
        if (installation != null) {
            installation.close();
            installation = null;
        }
    }

    public static void reset() {
        synchronized (ServiceContainer.class) {
            if (instance != null) {
                instance.hub.logout();
                if (instance.installation != null) {
                    instance.installation.close();
                }
                instance.shutdownExecutor();
                instance = null;
            }
        }
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}