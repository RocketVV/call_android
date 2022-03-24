package im.zego.call.ui.setting;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

import com.blankj.utilcode.util.ActivityUtils;
import com.tencent.mmkv.MMKV;

import im.zego.call.BuildConfig;
import im.zego.call.R;
import im.zego.call.ZegoCallKit;
import im.zego.call.databinding.ActivitySettingBinding;
import im.zego.call.ui.BaseActivity;
import im.zego.call.ui.login.GoogleLoginActivity;
import im.zego.call.ui.webview.WebViewActivity;
import im.zego.zegoexpress.ZegoExpressEngine;
import im.zego.zim.ZIM;
import im.zego.zim.enums.ZIMErrorCode;

public class SettingActivity extends BaseActivity<ActivitySettingBinding> {

    public static final String TERMS_OF_SERVICE = "https://www.zegocloud.com/policy?index=1";
    public static final String PRIVACY_POLICY = "https://www.zegocloud.com/policy?index=0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding.settingTitleBack.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        binding.expressSdkVersion.setText(ZegoExpressEngine.getVersion());
        binding.zimSdkVersion.setText(ZIM.getVersion());
//        binding.appVersion.setText(BuildConfig.VERSION_NAME);

        binding.termsService.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                WebViewActivity.startWebViewActivity(TERMS_OF_SERVICE);
            }
        });

        binding.privacyPolicy.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                WebViewActivity.startWebViewActivity(PRIVACY_POLICY);
            }
        });

        binding.uploadLog.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ZegoCallKit.getInstance().uploadLog(errorCode -> {
                    if (errorCode == ZIMErrorCode.SUCCESS.value()) {
                        showNormalTips(getString(R.string.toast_upload_log_success));
                    } else {
                        showWarnTips(getString(R.string.toast_upload_log_fail, errorCode));
                    }
                });
            }
        });

        binding.logOut.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ZegoCallKit.getInstance().uiKitService.logout();

                MMKV.defaultMMKV().encode("autoLogin", false);
                ActivityUtils.finishToActivity(GoogleLoginActivity.class, false);
            }
        });
    }
}