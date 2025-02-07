package easydarwin.android.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.videolan.vlc.R;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

public class EasyCameraApp extends Application {

	//public static final String PREVIEW_SIZE_VALUES = "preview-size-values";
	//public static final String PREVIEW_SIZE = "video_resolution";
//	public static final String KEY_SERVER_ADDRESS = "key_server_address";
//	public static final String KEY_SERVER_PORT = "key_server_port";
//	protected static final String KEY_DEVICE_ID = "key_device_id";
	public static final String KEY_TRANPORT = "key_transport_list";
	public static final String ACTION_COMMOND_STATE_CHANGED = "action_command_state_changed";
	public static final byte STATE_DISCONNECTED = 0;
	public static final byte STATE_CONNECTING = 1;
	public static final byte STATE_CONNECTED = 2;
	public static final String KEY_STATE = "KEY_STATE";
	public static final String KEY_SERVER_IP = "key_server_ip";
	/**
	 * current connect state
	 */
	public volatile static byte sState = STATE_DISCONNECTED;
	static String[] sEntries;

	Pattern pattern = Pattern.compile("([0-9]+)x([0-9]+)");
	
	@Override
	public void onCreate() {
		// TODO Auto-generated method stub
		super.onCreate();

		PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
		
		
		Matcher matcher = pattern.matcher(pref.getString(
				"video_resolution", "176x144"));
		matcher.find();

	}


	public static String getDefaultDeviceId() {
		return Build.MODEL.replaceAll(" ", "_");
	}

}
