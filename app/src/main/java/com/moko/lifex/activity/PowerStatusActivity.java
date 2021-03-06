package com.moko.lifex.activity;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.moko.lifex.AppConstants;
import com.moko.lifex.R;
import com.moko.lifex.base.BaseActivity;
import com.moko.lifex.entity.MQTTConfig;
import com.moko.lifex.entity.MokoDevice;
import com.moko.lifex.entity.MsgCommon;
import com.moko.lifex.entity.OverloadInfo;
import com.moko.lifex.entity.PowerStatus;
import com.moko.lifex.service.MokoService;
import com.moko.lifex.utils.SPUtiles;
import com.moko.lifex.utils.ToastUtils;
import com.moko.support.MokoConstants;
import com.moko.support.log.LogModule;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.lang.reflect.Type;

import butterknife.Bind;
import butterknife.ButterKnife;

/**
 * @Date 2018/6/7
 * @Author wenzheng.liu
 * @Description
 * @ClassPath com.moko.lifex.activity.PowerStatusActivity
 */
public class PowerStatusActivity extends BaseActivity {


    @Bind(R.id.rb_switch_off)
    RadioButton rbSwitchOff;
    @Bind(R.id.rb_switch_on)
    RadioButton rbSwitchOn;
    @Bind(R.id.rb_last_status)
    RadioButton rbLastStatus;
    @Bind(R.id.rg_power_status)
    RadioGroup rgPowerStatus;

    private MQTTConfig appMqttConfig;
    private MokoDevice mokoDevice;
    private MokoService mokoService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_power_status);
        ButterKnife.bind(this);
        if (getIntent().getExtras() != null) {
            int powerStatus = getIntent().getExtras().getInt(AppConstants.EXTRA_KEY_POWER_STATUS);
            mokoDevice = (MokoDevice) getIntent().getSerializableExtra(AppConstants.EXTRA_KEY_DEVICE);
            switch (powerStatus) {
                case 0:
                    rbSwitchOff.setChecked(true);
                    break;
                case 1:
                    rbSwitchOn.setChecked(true);
                    break;
                case 2:
                    rbLastStatus.setChecked(true);
                    break;
            }
            rgPowerStatus.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(RadioGroup group, int checkedId) {
                    if (!mokoService.isConnected()) {
                        ToastUtils.showToast(PowerStatusActivity.this, R.string.network_error);
                        return;
                    }
                    if (!mokoDevice.isOnline) {
                        ToastUtils.showToast(PowerStatusActivity.this, R.string.device_offline);
                        return;
                    }
                    if (mokoDevice.isOverload) {
                        ToastUtils.showToast(PowerStatusActivity.this, R.string.device_overload);
                        return;
                    }
                    showLoadingProgressDialog(getString(R.string.wait));
                    switch (checkedId) {
                        case R.id.rb_switch_off:
                            setPowerStatus(0);
                            break;
                        case R.id.rb_switch_on:
                            setPowerStatus(1);
                            break;
                        case R.id.rb_last_status:
                            setPowerStatus(2);
                            break;
                    }
                }
            });
            
            String mqttConfigAppStr = SPUtiles.getStringValue(this, AppConstants.SP_KEY_MQTT_CONFIG_APP, "");
            appMqttConfig = new Gson().fromJson(mqttConfigAppStr, MQTTConfig.class);

            bindService(new Intent(this, MokoService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        } else {
            finish();
            return;
        }

    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mokoService = ((MokoService.LocalBinder) service).getService();
            // 注册广播接收器
            IntentFilter filter = new IntentFilter();
            filter.addAction(MokoConstants.ACTION_MQTT_CONNECTION);
            filter.addAction(MokoConstants.ACTION_MQTT_RECEIVE);
            filter.addAction(MokoConstants.ACTION_MQTT_PUBLISH);
            filter.addAction(AppConstants.ACTION_DEVICE_STATE);
            registerReceiver(mReceiver, filter);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

        }
    };

    private void setPowerStatus(int status) {
        LogModule.i("设置上电状态");
        MsgCommon<PowerStatus> msgCommon = new MsgCommon();
        msgCommon.msg_id = MokoConstants.MSG_ID_A_2_D_SET_POWER_STATUS;
        msgCommon.id = mokoDevice.uniqueId;
        PowerStatus powerStatus = new PowerStatus();
        powerStatus.switch_state = status;
        msgCommon.data = powerStatus;
        MqttMessage message = new MqttMessage();
        message.setPayload(new Gson().toJson(msgCommon).getBytes());
        message.setQos(appMqttConfig.qos);
        String appTopic;
        if (TextUtils.isEmpty(appMqttConfig.topicPublish)) {
            appTopic = mokoDevice.topicSubscribe;
        } else {
            appTopic = appMqttConfig.topicPublish;
        }
        try {
            mokoService.publish(appTopic, message);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MokoConstants.ACTION_MQTT_CONNECTION.equals(action)) {
                int state = intent.getIntExtra(MokoConstants.EXTRA_MQTT_CONNECTION_STATE, 0);
            }
            if (MokoConstants.ACTION_MQTT_RECEIVE.equals(action)) {
                String topic = intent.getStringExtra(MokoConstants.EXTRA_MQTT_RECEIVE_TOPIC);
                String message = intent.getStringExtra(MokoConstants.EXTRA_MQTT_RECEIVE_MESSAGE);
                MsgCommon<JsonObject> msgCommon;
                try {
                    Type type = new TypeToken<MsgCommon<JsonObject>>() {
                    }.getType();
                    msgCommon = new Gson().fromJson(message, type);
                } catch (Exception e) {
                    return;
                }
                if (mokoDevice.uniqueId.equals(msgCommon.id)) {
                    if (msgCommon.msg_id == MokoConstants.MSG_ID_D_2_A_SWITCH_STATE) {
                        mokoDevice.isOnline = true;
                        rbSwitchOff.setEnabled(true);
                        rbSwitchOn.setEnabled(true);
                        rbLastStatus.setEnabled(true);
                    }
                    if (msgCommon.msg_id == MokoConstants.MSG_ID_D_2_A_OVERLOAD) {
                        Type infoType = new TypeToken<OverloadInfo>() {
                        }.getType();
                        OverloadInfo overLoadInfo = new Gson().fromJson(msgCommon.data, infoType);
                        mokoDevice.isOverload = overLoadInfo.overload_state == 1;
                        mokoDevice.overloadValue = overLoadInfo.overload_value;
                    }
                }
            }
            if (MokoConstants.ACTION_MQTT_PUBLISH.equals(action)) {
                int state = intent.getIntExtra(MokoConstants.EXTRA_MQTT_STATE, 0);
                if (state == MokoConstants.MQTT_STATE_SUCCESS) {
                    dismissLoadingProgressDialog();
                    LogModule.i("设置上电状态成功");
                }

            }
            if (AppConstants.ACTION_DEVICE_STATE.equals(action)) {
                String topic = intent.getStringExtra(MokoConstants.EXTRA_MQTT_RECEIVE_TOPIC);
                if (topic.equals(mokoDevice.topicPublish)) {
                    mokoDevice.isOnline = false;
                    rbSwitchOff.setEnabled(false);
                    rbSwitchOn.setEnabled(false);
                    rbLastStatus.setEnabled(false);
                }
            }
        }
    };

    public void back(View view) {
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
        unbindService(serviceConnection);
    }
}
