package easydarwin.android.service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

class ReceiveUnit {
	// Status code definitions
	public static final String STATUS_OK = "200 OK";
	public static final String STATUS_BAD_REQUEST = "400 Bad Request";
	public static final String STATUS_NOT_FOUND = "404 Not Found";
	public static final String STATUS_INTERNAL_SERVER_ERROR = "500 Internal Server Error";
	
	protected static final String KEY_TRANPORT = "key_transport_list";
	protected static final String ACTION_COMMOND_STATE_CHANGED = "action_command_state_changed";
	protected static final byte STATE_DISCONNECTED = 0;
	protected static final byte STATE_CONNECTING = 1;
	protected static final byte STATE_CONNECTED = 2;
	protected static final String KEY_STATE = "KEY_STATE";
	protected static final String KEY_SERVER_IP = "key_server_ip";
	/* current connect state */
	protected volatile static byte sState = STATE_DISCONNECTED;

	public int status = 200;
	public String method;
	public String uri;

	// Parse method & uri
	public static final Pattern regexMethod = Pattern.compile("(\\w+) (\\S+) RTSP", Pattern.CASE_INSENSITIVE);
	// Parse a request header
	public static final Pattern rexegHeader = Pattern.compile("(\\S+):(.+)", Pattern.CASE_INSENSITIVE);
	// Parses method & uri
	public static final Pattern regexStatus = Pattern.compile("RTSP/\\d.\\d (\\d+) (\\w+)", Pattern.CASE_INSENSITIVE);
	// Parses a WWW-Authenticate header
	public static final Pattern rexegAuthenticate = Pattern.compile("realm=\"(.+)\",\\s+nonce=\"(\\w+)\"", Pattern.CASE_INSENSITIVE);
	// Parses a Session header
	public static final Pattern rexegSession = Pattern.compile("(\\d+)", Pattern.CASE_INSENSITIVE);
	// Parses a Transport header
	public static final Pattern rexegTransport = Pattern.compile("client_port=(\\d+)-(\\d+).+server_port=(\\d+)-(\\d+)", Pattern.CASE_INSENSITIVE);

	public HashMap<String, String> headers = new HashMap<String, String>();

	public static ReceiveUnit parseReader(BufferedReader input) throws IOException, IllegalStateException, SocketException {
		ReceiveUnit unit = new ReceiveUnit();
		String line;
		Matcher matcher;

		// Parsing request method & uri
		if ((line = input.readLine()) == null)
			throw new SocketException("Client disconnected");
		Log.e("ReceiveUnit", line);
		matcher = regexMethod.matcher(line);
		if (matcher.find()) { // request
			unit.method = matcher.group(1);
			unit.uri = matcher.group(2);

			// Parsing headers of the request
			while ((line = input.readLine()) != null && line.length() > 3) {
				Log.e("ReceiveUnit", line);
				matcher = rexegHeader.matcher(line);
				matcher.find();
				unit.headers.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
			}
			if (line == null)
				throw new SocketException("Client disconnected");
		} else { // response
			matcher = regexStatus.matcher(line);
			if (matcher.find()) {
				unit.status = Integer.parseInt(matcher.group(1));

				// Parsing headers of the request
				while ((line = input.readLine()) != null) {
					Log.d("ReceiveUnit", line);
					// Log.e(TAG,"l: "+line.length()+", c: "+line);
					if (line.length() > 3) {
						matcher = rexegHeader.matcher(line);
						matcher.find();
						unit.headers.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
					} else {
						break;
					}
				}
				if (line == null)
					throw new SocketException("Connection lost");

			}
		}
		return unit;
	}

	static ByteBuffer buffer = ByteBuffer.allocate(1024);

	public static ReceiveUnit parseReader(SocketChannel channel) throws IOException, IllegalStateException, SocketException {
		ReceiveUnit unit = new ReceiveUnit();
		String line;
		Matcher matcher;
		buffer.clear();
		channel.read(buffer);
		buffer.flip();
		ByteArrayInputStream bis = new ByteArrayInputStream(buffer.array(), buffer.position(), buffer.limit());
		BufferedReader reader = new BufferedReader(new InputStreamReader(bis));

		// Parsing request method & uri
		if ((line = reader.readLine()) == null)
			throw new SocketException("Client disconnected");
		Log.e("ReceiveUnit", line);
		matcher = regexMethod.matcher(line);
		if (matcher.find()) { // request
			unit.method = matcher.group(1);
			unit.uri = matcher.group(2);

			// Parsing headers of the request
			while ((line = reader.readLine()) != null && line.length() > 3) {
				Log.e("ReceiveUnit", line);
				matcher = rexegHeader.matcher(line);
				matcher.find();
				unit.headers.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
			}
			if (line == null)
				throw new SocketException("Client disconnected");
		} else { // response
			matcher = regexStatus.matcher(line);
			if (matcher.find()) {
				unit.status = Integer.parseInt(matcher.group(1));

				// Parsing headers of the request
				while ((line = reader.readLine()) != null) {
					Log.d("ReceiveUnit", line);
					// Log.e(TAG,"l: "+line.length()+", c: "+line);
					if (line.length() > 3) {
						matcher = rexegHeader.matcher(line);
						matcher.find();
						unit.headers.put(matcher.group(1).toLowerCase(Locale.US), matcher.group(2));
					} else {
						break;
					}
				}
				if (line == null)
					throw new SocketException("Connection lost");

			}
		}
		return unit;
	}

}

public class CommandService extends Service {
	public class BlockCommandThread extends Thread {

		private Thread mWriteThread;

		@Override
		public void run() {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			Intent i = new Intent(EasyCameraApp.ACTION_COMMOND_STATE_CHANGED);
			i.putExtra(EasyCameraApp.KEY_STATE, EasyCameraApp.STATE_CONNECTING);
			EasyCameraApp.sState = EasyCameraApp.STATE_CONNECTING;
			LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);
			Socket cSocket = null;
			try {
				SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(CommandService.this);
				String ip = pref.getString("key_server_address", "129.128.184.46");
				String port = pref.getString("key_server_port", "8554");
				String id = pref.getString("key_device_id", Build.MODEL);

				InetAddress inetAddress = InetAddress.getByName(ip);

				byte[] addr = inetAddress.getAddress();
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < addr.length; j++) {
					short s = (short) (addr[j] >= 0 ? addr[j] : 256 + addr[j]);
					sb.append(s);
					if (j != addr.length - 1) {
						sb.append('.');
					}
				}
				log.info(String.format("started, ip:%s,port:%s,id:%s", ip, port, id));
				ip = sb.toString();
				pref.edit().putString(EasyCameraApp.KEY_SERVER_IP, ip).commit();
				int nPort = Integer.parseInt(port);
				cSocket = new Socket(inetAddress, nPort);
				cSocket.setSoTimeout(60 * 1000);
				BufferedReader reader = new BufferedReader(new InputStreamReader(cSocket.getInputStream()));

				final OutputStream os = cSocket.getOutputStream();
				// send request
				final String uri = "rtsp://" + ip + ":" + port + "/" + id + ".sdp";
				String report = String.format("OPTIONS %s RTSP/1.0\r\nCSeq:1\r\nx-msg-type: DEV_MSG\r\nx-dev-id: %s\r\n\r\n", uri, id);
				// String report = "OPTIONS " + "rtsp://" + ip + ":" +
				// port + "/" + id + ".sdp RTSP/1.0\r\nCSeq: " + 1 +
				// "\r\nx-msg-type: DEV_MSG\r\nx-dev-id: live\r\n\r\n";

				os.write(report.getBytes());

				// receive response
				ReceiveUnit unit = ReceiveUnit.parseReader(reader);
				if (unit.status != 200) {
					return;
				}
				// connect success
				mRequestQueue = new ArrayBlockingQueue<String>(10);
				mWriteThread = new Thread("KEEPALIVE") {

					@Override
					public void run() {
						final String keepalive = "OPTIONS " + uri + " RTSP/1.0\r\n" + "CSeq: " + 2 + "\r\n\r\n";
						while (mWriteThread != null) {
							try {
								String request = mRequestQueue.poll(15, TimeUnit.SECONDS);
								if (request == null) {
									request = keepalive;
								}
								os.write(request.getBytes());
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								//e.printStackTrace();
							} catch (IOException e) {
								// TODO Auto-generated catch block
								//e.printStackTrace();
							}
						}
					}

				};
				mWriteThread.start();
				i = new Intent(EasyCameraApp.ACTION_COMMOND_STATE_CHANGED);
				i.putExtra(EasyCameraApp.KEY_STATE, EasyCameraApp.STATE_CONNECTED);
				LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);
				EasyCameraApp.sState = EasyCameraApp.STATE_CONNECTED;
				while (mReadThread != null) {
					try {
						unit = ReceiveUnit.parseReader(reader);
						if (!TextUtils.isEmpty(unit.method)) {
							i = new Intent(unit.method);
							Iterator<String> it = unit.headers.keySet().iterator();
							while (it.hasNext()) {
								String key = it.next();
								String value = unit.headers.get(key);
								i.putExtra(key, value);
								it.remove();
							}
							if ("REDIRECT".equals(unit.method)) {
								i.putExtra("path", id);
							}
							boolean result = LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);

							if ("REDIRECT".equals(unit.method)) {
								if (result) {
									mRequestQueue.offer("RTSP/1.0 200 OK\r\nCseq: " + unit.headers.get("cseq") + "\r\n\r\n");
								} else {
									mRequestQueue.offer("RTSP/1.0 400 STATUS_BAD_REQUEST\r\nCseq: " + unit.headers.get("cseq") + "\r\n\r\n");
								}
							}
						}
						// if ("REDIRECT".equals(unit.method)) {
						// mClient.setCredentials("", "");
						// mClient.setServerAddress(IP, 8554);
						// mClient.setStreamPath("/live.sdp");
						// mClient.startStream();
						// mRequestQueue.offer("RTSP/1.0 200 OK\r\nCseq: "
						// + unit.headers.get("cseq") + "\r\n\r\n");
						// }
					} catch (SocketException e) {
						// Client has left
						//e.printStackTrace();
						break;
					} catch (Exception e) {// Client has left
						//e.printStackTrace();
						break;
					}
				}

			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			} finally {
				if (cSocket != null) {
					try {
						cSocket.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						//e.printStackTrace();
					}
					cSocket = null;
				}
				Thread thread = mWriteThread;
				if (thread != null) {
					mWriteThread = null;
					thread.interrupt();
					try {
						thread.join();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						//e.printStackTrace();
					}
				}
				EasyCameraApp.sState = EasyCameraApp.STATE_DISCONNECTED;
				if (mReadThread != null) {
					i = new Intent(EasyCameraApp.ACTION_COMMOND_STATE_CHANGED);
					i.putExtra(EasyCameraApp.KEY_STATE, EasyCameraApp.STATE_DISCONNECTED);
					LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);
					mReadThread = null;
				} else { // close by hand

				}
			}
		}

	}

	private static final Logger log = Logger.getLogger("COMMAND_SERVICE");
	Thread mReadThread;
	protected ArrayBlockingQueue<String> mRequestQueue;
	public String mUri;
	private String mKeepalive;
	/**
	 * Last Write Time
	 */
	private long mLastWriteTime;

	public CommandService() {
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int result = super.onStartCommand(intent, flags, startId);
		if (mReadThread == null) {
			mReadThread = new NoblockCommandThread();
			mReadThread.start();
		}
		return result;
	}

	@Override
	public void onDestroy() {
		log.info("onDestroy");
		Thread thread = mReadThread;
		if (thread != null) {
			mReadThread = null;
			thread.interrupt();
			try {
				thread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
		}
		super.onDestroy();
	}

	class NoblockCommandThread extends Thread {

		@Override
		public void run() {
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
			Intent i = new Intent(EasyCameraApp.ACTION_COMMOND_STATE_CHANGED);
			i.putExtra(EasyCameraApp.KEY_STATE, EasyCameraApp.STATE_CONNECTING);
			EasyCameraApp.sState = EasyCameraApp.STATE_CONNECTING;
			LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);
			SocketChannel sc = null;
			Selector selector = null;
			try {
				SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(CommandService.this);
//				final String ip = pref.getString(EasyCameraApp.KEY_SERVER_ADDRESS, "www.easydarwin.org");
//				String port = pref.getString(EasyCameraApp.KEY_SERVER_PORT, "554");
//				String id = pref.getString(EasyCameraApp.KEY_DEVICE_ID, Build.MODEL);
				final String ip = pref.getString("key_server_address", "129.128.184.46");
				String port = pref.getString("key_server_port", "8554");
				String id = pref.getString("key_device_id", Build.MODEL);
				
				final InetAddress[] inetAddress = new InetAddress[1];
				
				Thread t = new Thread("getByName") {

					@Override
					public void run() {
						try {
							inetAddress[0] = InetAddress.getByName(ip);
						} catch (UnknownHostException e) {
							// TODO Auto-generated catch block
							//e.printStackTrace();
						}
					}

				};
				t.start();
				Thread.yield();
				t.join();
				if (inetAddress[0] == null) {
					return;
				}
				byte[] addr = inetAddress[0].getAddress();
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < addr.length; j++) {
					short s = (short) (addr[j] >= 0 ? addr[j] : 256 + addr[j]);
					sb.append(s);
					if (j != addr.length - 1) {
						sb.append('.');
					}
				}
				log.info(String.format("started, ip:%s,port:%s,id:%s", ip, port, id));
				pref.edit().putString(EasyCameraApp.KEY_SERVER_IP, sb.toString()).commit();
				int nPort = Integer.parseInt(port);
				sc = SocketChannel.open();
				sc.configureBlocking(false);
				sc.connect(new InetSocketAddress(inetAddress[0], nPort));
				while (mReadThread != null && !sc.finishConnect()) {
					Thread.sleep(3);
				}

				selector = Selector.open();
				sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

				// send request
				mUri = "rtsp://" + ip + ":" + port + "/" + id + ".sdp";
				mKeepalive = "OPTIONS " + mUri + " RTSP/1.0\r\n" + "CSeq: " + 2 + "\r\n\r\n";
				mRequestQueue = new ArrayBlockingQueue<String>(1000);
				mRequestQueue.offer(String.format("OPTIONS %s RTSP/1.0\r\nCSeq:1\r\nx-msg-type: DEV_MSG\r\nx-dev-id: %s\r\n\r\n", mUri, id));
				boolean registed = false;
				while (mReadThread != null) {
					int num = selector.selectNow();
					if (num > 0) {
						Iterator<SelectionKey> it = selector.selectedKeys().iterator();
						while (mReadThread != null && it.hasNext()) {
							SelectionKey selectionKey = it.next();
							if (selectionKey.isReadable()) {
								if (!registed) {
									ReceiveUnit unit = ReceiveUnit.parseReader((SocketChannel) selectionKey.channel());
									if (unit.status != 200) {
										return;
									}

									i = new Intent(EasyCameraApp.ACTION_COMMOND_STATE_CHANGED);
									i.putExtra(EasyCameraApp.KEY_STATE, EasyCameraApp.STATE_CONNECTED);
									LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);
									EasyCameraApp.sState = EasyCameraApp.STATE_CONNECTED;
									registed = true;
								} else {
									ReceiveUnit unit = ReceiveUnit.parseReader((SocketChannel) selectionKey.channel());
									if (!TextUtils.isEmpty(unit.method)) {
										i = new Intent(unit.method);
										Iterator<String> it1 = unit.headers.keySet().iterator();
										while (it1.hasNext()) {
											String key = it1.next();
											String value = unit.headers.get(key);
											i.putExtra(key, value);
											it1.remove();
										}
										if ("REDIRECT".equals(unit.method)) {
											i.putExtra("path", String.format("/%s.sdp", id));
										}
										boolean result = LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);

										if ("REDIRECT".equals(unit.method)) {
											if (result) {
												mRequestQueue.offer("RTSP/1.0 200 OK\r\nCseq: " + unit.headers.get("cseq") + "\r\n\r\n");
											} else {
												mRequestQueue.offer("RTSP/1.0 400 STATUS_BAD_REQUEST\r\nCseq: " + unit.headers.get("cseq") + "\r\n\r\n");
											}
										}
									}
								}
							}
							if (selectionKey.isWritable()) {
								tryWriteOut((SocketChannel) selectionKey.channel());
							}
							it.remove();
						}
					}
					Thread.sleep(100);
				}

			} catch (UnknownHostException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			} catch (InterruptedException e) {
				//e.printStackTrace();
			} finally {
				if (sc != null) {
					try {
						sc.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						//e.printStackTrace();
					}
					sc = null;
				}
				if (selector != null) {
					try {
						selector.close();
					} catch (IOException e) {
						//e.printStackTrace();
					}
				}
				EasyCameraApp.sState = EasyCameraApp.STATE_DISCONNECTED;
				if (mReadThread != null) {
					i = new Intent(EasyCameraApp.ACTION_COMMOND_STATE_CHANGED);
					i.putExtra(EasyCameraApp.KEY_STATE, EasyCameraApp.STATE_DISCONNECTED);
					LocalBroadcastManager.getInstance(CommandService.this).sendBroadcast(i);
					mReadThread = null;
				} else { // close by hand

				}
			}
		}

	}

	public void tryWriteOut(SocketChannel channel) throws IOException {
		String requet = mRequestQueue.poll();
		if (requet == null) {
			if (SystemClock.uptimeMillis() - mLastWriteTime < 15000) {
				return;
			}
			requet = mKeepalive;
		}
		ByteBuffer buffer = ByteBuffer.wrap(requet.getBytes());
		while (mReadThread != null && buffer.hasRemaining()) {
			channel.write(buffer);
		}
		mLastWriteTime = SystemClock.uptimeMillis();
	}

}
