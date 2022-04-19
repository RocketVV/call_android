package im.zego.calluikit;

import android.app.Activity;
import android.app.Application;

import im.zego.callsdk.callback.ZegoCallback;
import im.zego.callsdk.listener.ZegoCallServiceListener;
import im.zego.callsdk.model.ZegoUserInfo;

/**
 * ZegoCall UIKit管理类 Demo层只需调用并关注此类的实现，即可快速实现一套呼叫对讲业务逻辑
 */
public class ZegoCallManager {

    private static final String TAG = "ZegoCallManager";

    private static volatile ZegoCallManager singleton = null;
    public String token;

    private ZegoCallManager() {
        impl = new ZegoCallManagerImpl();
    }

    public static ZegoCallManager getInstance() {
        if (singleton == null) {
            synchronized (ZegoCallManager.class) {
                if (singleton == null) {
                    singleton = new ZegoCallManager();
                }
            }
        }
        return singleton;
    }

    // CallKit服务类
    private ZegoCallManagerImpl impl;

    private ZegoCallTokenDelegate tokenDelegate;

    /**
     * 初始化sdk与rtc引擎 调用时机：应用启动时
     */
    public void init(long appID, Application application) {
        impl.init(appID, application);
    }

    public void unInit() {
        impl.unInit();
    }

    public void setListener(ZegoCallServiceListener listener) {
        impl.setListener(listener);
    }

    public void setTokenDelegate(ZegoCallTokenDelegate tokenDelegate) {
        this.tokenDelegate = tokenDelegate;
    }

    public ZegoCallTokenDelegate getTokenDelegate() {
        return tokenDelegate;
    }

    /**
     * 启动监听呼叫响应 调用时机：成功登录之后
     */
    public void startListen(Activity activity) {
        impl.startListen(activity);
    }

    /**
     * 停止监听呼叫响应 调用时机：退出登录之后
     */
    public void stopListen(Activity activity) {
        impl.stopListen(activity);
    }

    /**
     * 上传日志
     *
     * @param callback
     */
    public void uploadLog(final ZegoCallback callback) {
        impl.uploadLog(callback);
    }

    /**
     * 主动呼叫用户
     *
     * @param userInfo  用户信息
     * @param callState 呼叫类型，语音/视频
     */
    public void callUser(ZegoUserInfo userInfo, int callState) {
        impl.callUser(userInfo, callState);
    }

    /**
     * 获取本地用户信息
     */
    public ZegoUserInfo getLocalUserInfo() {
        return impl.getLocalUserInfo();
    }

    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 展示前台服务通知 调用时机：应用切换到后台后
     */
    public void showNotification(ZegoUserInfo userInfo) {
        impl.showNotification(userInfo);
    }

    /**
     * 隐藏前台服务通知 调用时机：应用切换到前台后
     */
    public void dismissNotification(Activity activity) {
        impl.dismissNotification(activity);
    }

    public void dismissCallDialog() {
        impl.dismissCallDialog();
    }
}
