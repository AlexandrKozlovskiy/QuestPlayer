package com.qsp.player.libqsp;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.qsp.player.R;
import com.qsp.player.libqsp.dto.ActionData;
import com.qsp.player.libqsp.dto.ErrorData;
import com.qsp.player.libqsp.dto.GetVarValuesResponse;
import com.qsp.player.libqsp.dto.ObjectData;
import com.qsp.player.libqsp.model.GameState;
import com.qsp.player.libqsp.model.InterfaceConfiguration;
import com.qsp.player.libqsp.model.QspListItem;
import com.qsp.player.libqsp.model.QspMenuItem;
import com.qsp.player.libqsp.model.RefreshInterfaceRequest;
import com.qsp.player.libqsp.model.WindowType;
import com.qsp.player.service.AudioPlayer;
import com.qsp.player.service.GameContentResolver;
import com.qsp.player.service.HtmlProcessor;
import com.qsp.player.service.ImageProvider;
import com.qsp.player.util.StreamUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import static com.qsp.player.util.FileUtil.findFileOrDirectory;
import static com.qsp.player.util.FileUtil.getFileContents;
import static com.qsp.player.util.FileUtil.getOrCreateDirectory;
import static com.qsp.player.util.StringUtil.getStringOrEmpty;
import static com.qsp.player.util.StringUtil.isNotEmpty;
import static com.qsp.player.util.ThreadUtil.isSameThread;
import static com.qsp.player.util.ThreadUtil.throwIfNotMainThread;

public class LibQspProxyImpl implements LibQspProxy, LibQspCallbacks {
    private static final Logger logger = LoggerFactory.getLogger(LibQspProxyImpl.class);

    private final ReentrantLock libQspLock = new ReentrantLock();
    private final GameState gameState = new GameState();
    private final NativeMethods nativeMethods = new NativeMethods(this);

    private Thread libQspThread;
    private volatile Handler libQspHandler;
    private volatile boolean libQspThreadInited;
    private volatile long gameStartTime;
    private volatile long lastMsCountCallTime;
    private GameInterface gameInterface;

    private final Context context;
    private final GameContentResolver gameContentResolver;
    private final ImageProvider imageProvider;
    private final HtmlProcessor htmlProcessor;
    private final AudioPlayer audioPlayer;

    public LibQspProxyImpl(
            Context context,
            GameContentResolver gameContentResolver,
            ImageProvider imageProvider,
            HtmlProcessor htmlProcessor,
            AudioPlayer audioPlayer) {
        this.context = context;
        this.gameContentResolver = gameContentResolver;
        this.imageProvider = imageProvider;
        this.htmlProcessor = htmlProcessor;
        this.audioPlayer = audioPlayer;
    }

    private void runOnQspThread(final Runnable runnable) {
        throwIfNotMainThread();

        if (libQspThread == null) {
            logger.warn("libqsp thread has not been started");
            return;
        }
        if (!libQspThreadInited) {
            logger.warn("libqsp thread has been started, but not initialized");
            return;
        }
        Handler handler = libQspHandler;
        if (handler != null) {
            handler.post(() -> {
                libQspLock.lock();
                try {
                    runnable.run();
                } finally {
                    libQspLock.unlock();
                }
            });
        }
    }

    private boolean loadGameWorld() {
        byte[] gameData;
        try (FileInputStream in = new FileInputStream(gameState.getGameFile())) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                StreamUtil.copy(in, out);
                gameData = out.toByteArray();
            }
        } catch (IOException ex) {
            logger.error("Failed to load the game world", ex);
            return false;
        }
        String fileName = gameState.getGameFile().getAbsolutePath();
        if (!nativeMethods.QSPLoadGameWorldFromData(gameData, gameData.length, fileName)) {
            showLastQspError();
            return false;
        }

        return true;
    }

    private void showLastQspError() {
        ErrorData errorData = (ErrorData) nativeMethods.QSPGetLastErrorData();
        String locName = getStringOrEmpty(errorData.getLocName());
        String desc = getStringOrEmpty(nativeMethods.QSPGetErrorDesc(errorData.getErrorNum()));

        final String message = String.format(
                Locale.getDefault(),
                "Location: %s\nAction: %d\nLine: %d\nError number: %d\nDescription: %s",
                locName,
                errorData.getIndex(),
                errorData.getLine(),
                errorData.getErrorNum(),
                desc);

        logger.error(message);

        GameInterface inter = gameInterface;
        if (inter != null) {
            gameInterface.showError(message);
        }
    }

    /**
     * Загружает конфигурацию интерфейса - использование HTML, шрифт и цвета - из библиотеки.
     *
     * @return <code>true</code> если конфигурация изменилась, иначе <code>false</code>
     */
    private boolean loadInterfaceConfiguration() {
        InterfaceConfiguration config = gameState.getInterfaceConfig();
        boolean changed = false;

        GetVarValuesResponse htmlResult = (GetVarValuesResponse) nativeMethods.QSPGetVarValues("USEHTML", 0);
        if (htmlResult.isSuccess()) {
            boolean useHtml = htmlResult.getIntValue() != 0;
            if (config.isUseHtml() != useHtml) {
                config.setUseHtml(useHtml);
                changed = true;
            }
        }
        GetVarValuesResponse fSizeResult = (GetVarValuesResponse) nativeMethods.QSPGetVarValues("FSIZE", 0);
        if (fSizeResult.isSuccess() && config.getFontSize() != fSizeResult.getIntValue()) {
            config.setFontSize(fSizeResult.getIntValue());
            changed = true;
        }
        GetVarValuesResponse bColorResult = (GetVarValuesResponse) nativeMethods.QSPGetVarValues("BCOLOR", 0);
        if (bColorResult.isSuccess() && config.getBackColor() != bColorResult.getIntValue()) {
            config.setBackColor(bColorResult.getIntValue());
            changed = true;
        }
        GetVarValuesResponse fColorResult = (GetVarValuesResponse) nativeMethods.QSPGetVarValues("FCOLOR", 0);
        if (fColorResult.isSuccess() && config.getFontColor() != fColorResult.getIntValue()) {
            config.setFontColor(fColorResult.getIntValue());
            changed = true;
        }
        GetVarValuesResponse lColorResult = (GetVarValuesResponse) nativeMethods.QSPGetVarValues("LCOLOR", 0);
        if (lColorResult.isSuccess() && config.getLinkColor() != lColorResult.getIntValue()) {
            config.setLinkColor(lColorResult.getIntValue());
            changed = true;
        }

        return changed;
    }

    private ArrayList<QspListItem> getActions() {
        ArrayList<QspListItem> actions = new ArrayList<>();
        int count = nativeMethods.QSPGetActionsCount();
        for (int i = 0; i < count; ++i) {
            ActionData actionData = (ActionData) nativeMethods.QSPGetActionData(i);
            QspListItem action = new QspListItem();
            action.icon = imageProvider.get(actionData.getImage());
            action.text = gameState.getInterfaceConfig().isUseHtml() ? htmlProcessor.removeHtmlTags(actionData.getName()) : actionData.getName();
            actions.add(action);
        }
        return actions;
    }

    private ArrayList<QspListItem> getObjects() {
        ArrayList<QspListItem> objects = new ArrayList<>();
        int count = nativeMethods.QSPGetObjectsCount();
        for (int i = 0; i < count; i++) {
            ObjectData objectResult = (ObjectData) nativeMethods.QSPGetObjectData(i);
            QspListItem object = new QspListItem();
            object.icon = imageProvider.get(objectResult.getImage());
            object.text = gameState.getInterfaceConfig().isUseHtml() ? htmlProcessor.removeHtmlTags(objectResult.getName()) : objectResult.getName();
            objects.add(object);
        }
        return objects;
    }

    // region LibQspProxy

    public void start() {
        libQspThread = new Thread("libqsp") {
            @Override
            public void run() {
                try {
                    nativeMethods.QSPInit();
                    Looper.prepare();
                    libQspHandler = new Handler();
                    libQspThreadInited = true;

                    Looper.loop();

                    nativeMethods.QSPDeInit();
                } catch (Throwable t) {
                    logger.error("libqsp thread has stopped exceptionally", t);
                }
            }
        };
        libQspThread.start();
    }

    public void stop() {
        throwIfNotMainThread();

        if (libQspThread == null) return;

        if (libQspThreadInited) {
            Handler handler = libQspHandler;
            if (handler != null) {
                handler.getLooper().quitSafely();
            }
            libQspThreadInited = false;
        } else {
            logger.warn("libqsp thread has been started, but not initialized");
        }
        libQspThread = null;
    }

    @Override
    public void runGame(final String id, final String title, final File dir, final File file) {
        runOnQspThread(() -> doRunGame(id, title, dir, file));
    }

    private void doRunGame(final String id, final String title, final File dir, final File file) {
        gameInterface.doWithCounterDisabled(() -> {
            audioPlayer.closeAllFiles();

            gameState.reset();
            gameState.setGameRunning(true);
            gameState.setGameId(id);
            gameState.setGameTitle(title);
            gameState.setGameDir(dir);
            gameState.setGameFile(file);

            gameContentResolver.setGameDir(dir);
            imageProvider.invalidateCache();

            if (!loadGameWorld()) return;

            gameStartTime = SystemClock.elapsedRealtime();
            lastMsCountCallTime = 0;

            if (!nativeMethods.QSPRestartGame(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void restartGame() {
        runOnQspThread(() -> {
            GameState state = gameState;
            doRunGame(state.getGameId(), state.getGameTitle(), state.getGameDir(), state.getGameFile());
        });
    }

    @Override
    public void loadGameState(final Uri uri) {
        if (!isSameThread(libQspHandler.getLooper().getThread())) {
            runOnQspThread(() -> loadGameState(uri));
            return;
        }
        final byte[] gameData;

        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                StreamUtil.copy(in, out);
                gameData = out.toByteArray();
            }
        } catch (IOException ex) {
            logger.error("Failed to load game state", ex);
            return;
        }

        if (!nativeMethods.QSPOpenSavedGameFromData(gameData, gameData.length, true)) {
            showLastQspError();
        }
    }

    @Override
    public void saveGameState(final Uri uri) {
        if (!isSameThread(libQspHandler.getLooper().getThread())) {
            runOnQspThread(() -> saveGameState(uri));
            return;
        }
        byte[] gameData = nativeMethods.QSPSaveGameAsData(false);
        if (gameData == null) return;

        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
            out.write(gameData);
        } catch (IOException ex) {
            logger.error("Failed to save the game state", ex);
        }
    }

    @Override
    public void onActionSelected(final int index) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPSetSelActionIndex(index, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onActionClicked(final int index) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPSetSelActionIndex(index, false)) {
                showLastQspError();
            }
            if (!nativeMethods.QSPExecuteSelActionCode(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onObjectSelected(final int index) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPSetSelObjectIndex(index, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void onInputAreaClicked() {
        final GameInterface inter = gameInterface;
        if (inter == null) return;

        runOnQspThread(() -> {
            String input = inter.showInputBox(context.getString(R.string.userInput));
            nativeMethods.QSPSetInputStrText(input);

            if (!nativeMethods.QSPExecUserInput(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void execute(final String code) {
        runOnQspThread(() -> {
            if (!nativeMethods.QSPExecString(code, true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public void executeCounter() {
        if (libQspLock.isLocked()) return;

        runOnQspThread(() -> {
            if (!nativeMethods.QSPExecCounter(true)) {
                showLastQspError();
            }
        });
    }

    @Override
    public GameState getGameState() {
        return gameState;
    }

    @Override
    public void setGameInterface(GameInterface view) {
        gameInterface = view;
    }

    // endregion LibQspProxy

    // region LibQspCallbacks

    @Override
    public void RefreshInt() {
        RefreshInterfaceRequest request = new RefreshInterfaceRequest();

        boolean configChanged = loadInterfaceConfiguration();
        if (configChanged) {
            request.setInterfaceConfigChanged(true);
        }
        if (nativeMethods.QSPIsMainDescChanged()) {
            gameState.setMainDesc(nativeMethods.QSPGetMainDesc());
            request.setMainDescChanged(true);
        }
        if (nativeMethods.QSPIsActionsChanged()) {
            gameState.setActions(getActions());
            request.setActionsChanged(true);
        }
        if (nativeMethods.QSPIsObjectsChanged()) {
            gameState.setObjects(getObjects());
            request.setObjectsChanged(true);
        }
        if (nativeMethods.QSPIsVarsDescChanged()) {
            gameState.setVarsDesc(nativeMethods.QSPGetVarsDesc());
            request.setVarsDescChanged(true);
        }

        GameInterface inter = gameInterface;
        if (inter != null) {
            inter.refresh(request);
        }
    }

    @Override
    public void ShowPicture(String path) {
        GameInterface inter = gameInterface;
        if (inter != null && isNotEmpty(path)) {
            inter.showPicture(path);
        }
    }

    @Override
    public void SetTimer(int msecs) {
        GameInterface inter = gameInterface;
        if (inter != null) {
            inter.setCounterInterval(msecs);
        }
    }

    @Override
    public void ShowMessage(String message) {
        GameInterface inter = gameInterface;
        if (inter != null) {
            inter.showMessage(message);
        }
    }

    @Override
    public void PlayFile(String path, int volume) {
        if (isNotEmpty(path)) {
            audioPlayer.playFile(path, volume);
        }
    }

    @Override
    public boolean IsPlayingFile(final String path) {
        return isNotEmpty(path) && audioPlayer.isPlayingFile(path);
    }

    @Override
    public void CloseFile(String path) {
        if (isNotEmpty(path)) {
            audioPlayer.closeFile(path);
        } else {
            audioPlayer.closeAllFiles();
        }
    }

    @Override
    public void OpenGame(String filename) {
        File savesDir = getOrCreateDirectory(gameState.getGameDir(), "saves");
        File saveFile = findFileOrDirectory(savesDir, filename);
        if (saveFile == null) {
            logger.error("Save file not found: " + filename);
            return;
        }
        GameInterface inter = gameInterface;
        if (inter != null) {
            inter.doWithCounterDisabled(() -> loadGameState(Uri.fromFile(saveFile)));
        }
    }

    @Override
    public void SaveGame(String filename) {
        GameInterface inter = gameInterface;
        if (inter != null) {
            inter.showSaveGamePopup(filename);
        }
    }

    @Override
    public String InputBox(String prompt) {
        GameInterface inter = gameInterface;
        return inter != null ? inter.showInputBox(prompt) : null;
    }

    @Override
    public int GetMSCount() {
        long now = SystemClock.elapsedRealtime();
        if (lastMsCountCallTime == 0) {
            lastMsCountCallTime = gameStartTime;
        }
        int dt = (int) (now - lastMsCountCallTime);
        lastMsCountCallTime = now;

        return dt;
    }

    @Override
    public void AddMenuItem(String name, String imgPath) {
        QspMenuItem item = new QspMenuItem();
        item.imgPath = imgPath;
        item.name = name;
        gameState.getMenuItems().add(item);
    }

    @Override
    public void ShowMenu() {
        GameInterface inter = gameInterface;
        if (inter == null) return;

        int result = inter.showMenu();
        if (result != -1) {
            nativeMethods.QSPSelectMenuItem(result);
        }
    }

    @Override
    public void DeleteMenu() {
        gameState.getMenuItems().clear();
    }

    @Override
    public void Wait(int msecs) {
        try {
            Thread.sleep(msecs);
        } catch (InterruptedException ex) {
            logger.error("Wait failed", ex);
        }
    }

    @Override
    public void ShowWindow(int type, boolean isShow) {
        GameInterface inter = gameInterface;
        if (inter != null) {
            WindowType windowType = WindowType.values()[type];
            inter.showWindow(windowType, isShow);
        }
    }

    @Override
    public byte[] GetFileContents(String path) {
        return getFileContents(path);
    }

    @Override
    public void ChangeQuestPath(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            logger.error("Game directory not found: " + path);
            return;
        }
        if (!gameState.getGameDir().equals(dir)) {
            gameState.setGameDir(dir);
            gameContentResolver.setGameDir(dir);
            imageProvider.invalidateCache();
        }
    }

    // endregion LibQspCallbacks
}
