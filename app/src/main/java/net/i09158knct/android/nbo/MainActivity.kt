package net.i09158knct.android.nbo

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.graphics.PixelFormat
import android.view.*
import android.widget.Button
import android.graphics.Rect
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.location.LocationManager
import kotlinx.android.synthetic.main.view_notification_bar.view.*
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import android.os.PowerManager
import android.preference.PreferenceManager
import android.widget.LinearLayout
import android.widget.RelativeLayout
import kotlinx.android.synthetic.main.activity_main.*


class NBService : Service() {
    companion object {
        const val ACTION_SHOW = "net.i09158knct.android.nbo.NBService.ACTION_SHOW"
        const val ACTION_TOGGLE_SECOND_LABEL = "net.i09158knct.android.nbo.NBService.ACTION_TOGGLE_SECOND_LABEL"
    }

    lateinit var notificationLayout: RemoteViews


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationLayout = RemoteViews(packageName, R.layout.notification_small)

        val n = Notification.Builder(this).apply {
            setContentTitle("NBO")
            setContentText("Running...")
            setStyle(Notification.DecoratedCustomViewStyle())
            setCustomContentView(notificationLayout)
            setSmallIcon(R.drawable.ic_launcher_foreground)

            Intent(ACTION_TOGGLE_SECOND_LABEL).setPackage(packageName).apply {
                val pi = PendingIntent.getBroadcast(applicationContext, 1, this, PendingIntent.FLAG_UPDATE_CURRENT)
                notificationLayout.setOnClickPendingIntent(R.id.btnSeconds, pi)
            }

            val showi = Intent(ACTION_SHOW).putExtra("show", true).setPackage(packageName)
            val showpi = PendingIntent.getBroadcast(applicationContext, 1, showi, PendingIntent.FLAG_UPDATE_CURRENT)
            notificationLayout.setOnClickPendingIntent(R.id.btnShow, showpi)

            val hidei = Intent(ACTION_SHOW).putExtra("show", false).setPackage(packageName)
            val hidepi = PendingIntent.getBroadcast(applicationContext, 2, hidei, PendingIntent.FLAG_UPDATE_CURRENT)
            notificationLayout.setOnClickPendingIntent(R.id.btnHide, hidepi)
        }.build()

        startForeground(1, n)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        throw UnsupportedOperationException()
    }
}

class MainActivity : Activity() {
    private lateinit var bar: NotificationBar
    private lateinit var pref: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bar = NotificationBar(this)
        pref = PreferenceManager.getDefaultSharedPreferences(this)

        buttonGrantOverlayPermission.setOnClickListener {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(i)
        }

        buttonDisableBatteryOptimization.setOnClickListener {
            //            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
//            intent.data = Uri.parse("package:$packageName")
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }

        startService(Intent(this, NBService::class.java))

        var attached = false
        buttonShowNotificationBar.setOnClickListener {
            if (attached) windowManager.removeView(bar)
            bar.layoutParams.height = inputHeight.text.toString().toIntOrNull() ?: getStatusBarHeight()
            windowManager.addView(bar, bar.layoutParams)
            attached = true
        }

        inputHeight.setText(pref.getInt("height", 50).toString())
        btnDefaultHeight.setOnClickListener {
            val height = getStatusBarHeight()
            inputHeight.setText(height.toString())
            pref.edit().putInt("height", height).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        pref.edit().putInt("height", inputHeight.text.toString().toIntOrNull()
                ?: getStatusBarHeight()).apply()
    }

    fun getStatusBarHeight(): Int {
        val rect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(rect)
        return rect.top
    }


    override fun onDestroy() {
        super.onDestroy()
        if (bar.isAttachedToWindow) windowManager.removeViewImmediate(bar)
        bar.dispose()
    }
}

class NotificationBar(context: Context) : LinearLayout(context), Runnable {
    private val mReceiver = object : BroadcastReceiver() {
        init {
            context.registerReceiver(this, IntentFilter(Intent.ACTION_TIME_TICK));
            context.registerReceiver(this, IntentFilter(Intent.ACTION_TIME_CHANGED));
            context.registerReceiver(this, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED));
            context.registerReceiver(this, IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            context.registerReceiver(this, IntentFilter(Intent.ACTION_SCREEN_ON))
            context.registerReceiver(this, IntentFilter(Intent.ACTION_SCREEN_OFF))
            context.registerReceiver(this, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            context.registerReceiver(this, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            context.registerReceiver(this, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
            context.registerReceiver(this, IntentFilter(NBService.ACTION_SHOW))
            context.registerReceiver(this, IntentFilter(NBService.ACTION_TOGGLE_SECOND_LABEL))
        }

        override fun onReceive(arg0: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_SCREEN_ON -> {
                    if (!pm.isInteractive) return

                    val needSecondTickLoop = updateTime()
                    if (!running && needTickPerSecond) {
                        postDelayed(this@NotificationBar, 1000)
                    }
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isChaging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val str = "${level.toString().padStart(3)}%" + (if (isChaging) "!" else " ")
                    txtBattery.text = str
                }
                ConnectivityManager.CONNECTIVITY_ACTION -> updateNetwork()
                BluetoothAdapter.ACTION_STATE_CHANGED -> updateBluetooth()
                LocationManager.PROVIDERS_CHANGED_ACTION -> updateGps()
                NBService.ACTION_SHOW -> {
                    val show = intent.extras.getBoolean("show", true)
                    visibility = if (show) View.VISIBLE else View.INVISIBLE
                }
                NBService.ACTION_TOGGLE_SECOND_LABEL -> {
                    needTickPerSecond = !needTickPerSecond
                    updateTime()
                    if (needTickPerSecond && !running) {
                        postDelayed(this@NotificationBar, 900)
                    }
                }
            }
        }
    }

    fun dispose() {
        context.unregisterReceiver(mReceiver)
    }

    val timeFormat = SimpleDateFormat("HH:mm")
    val timeFormatMorning = SimpleDateFormat("HH:mm:ss")
    val h = Handler()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    var running = false
    var needTickPerSecond = false
    override fun run() {
        if (!needTickPerSecond || !pm.isInteractive) {
            running = false
            return
        }
        running = true

        updateTime()
        h.postDelayed(this, 1000)
    }

    private val emptyString = ""
    private var networkText = ""
    private fun updateNetwork() {
        var text = emptyString
        val info = cm.activeNetworkInfo
        val isAirplaneMode = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0) != 0

        if (info == null && isAirplaneMode) text = emptyString
        else if (info == null) text = "(No Signal)"
        else text = info.typeName

        if (isAirplaneMode) {
            text = "Airplane $text".trim()
        }

        if (!networkText.equals(text)) {
            networkText = text
            txtNetwork.text = text
        }
    }

    private var timeText = ""
    private fun updateTime() {
        val cal = Calendar.getInstance()
        val current = cal.time

        val text =
                if (needTickPerSecond) timeFormatMorning.format(current)
                else timeFormat.format(current)

        if (!timeText.equals(text)) {
            timeText = text
            txtTime.text = text
        }
    }

    private fun updateBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        txtBluetooth.text = if (adapter?.isEnabled == true) "B" else ""
    }

    private fun updateGps() {
        val m = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        txtGps.text = if (enabled) "GPS" else ""
    }


    init {
        layoutParams = WindowManager.LayoutParams().apply {
            gravity = Gravity.TOP
            dimAmount = 0f
            type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            flags =
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }


        inflate(context, R.layout.view_notification_bar, this)
        updateNetwork()
        updateBluetooth()
        updateGps()
        updateTime()
        if (needTickPerSecond) run()
    }
}
