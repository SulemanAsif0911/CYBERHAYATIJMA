package com.cyberhayat.ijma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service: while the app is closed it asks the Apps Script server for new messages every 10 s
 * (same idea as WatchService in Nijah) and shows a notification. While the app is on screen it stays idle,
 * because the page syncs by itself.
 */
class SyncService : Service() {

    companion object {
        @Volatile var appVisible = false
        @Volatile var running = false
        fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("ijma", Context.MODE_PRIVATE)
        fun start(c: Context) {
            val i = Intent(c, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, SyncService::class.java)) }
    }

    @Volatile private var alive = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels()
        val n: Notification = NotificationCompat.Builder(this, "service")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Cyber Hayat Ijmāʿ")
            .setContentText("Connected. Waiting for messages.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        running = true
        if (!alive) {
            alive = true
            worker = Thread {
                while (alive) {
                    try { if (!appVisible) poll() } catch (_: Exception) {}
                    try { Thread.sleep(10000) } catch (_: InterruptedException) {}
                }
            }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("service", "Background connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("calls", "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain")
        c.outputStream.use { it.write(body.toByteArray()) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun poll() {
        val p = prefs(this)
        val cfg = JSONObject(p.getString("cfg", "") ?: return)
        val url = cfg.optString("url")
        val me = cfg.optString("userId")
        if (url.isEmpty() || me.isEmpty()) return
        val names = cfg.optJSONObject("names") ?: JSONObject()
        val cur = JSONObject(p.getString("ncur", "{}") ?: "{}")
        // the page's own cursors win when they are ahead (messages the person already has)
        cfg.optJSONObject("cursors")?.let { js ->
            val it = js.keys()
            while (it.hasNext()) { val k = it.next(); cur.put(k, maxOf(cur.optInt(k, 0), js.optInt(k, 0))) }
        }
        val req = JSONObject()
            .put("action", "sync").put("userId", me).put("token", cfg.optString("token")).put("cursors", cur)
        val res = JSONObject(post(url, req.toString()))
        if (!res.optBoolean("ok")) return
        val calls = res.optJSONArray("calls")
        if (calls != null && calls.length() > 0) {
            val c = calls.getJSONObject(0)
            val id = c.optString("callId")
            if (id != p.getString("lastCall", "")) { p.edit().putString("lastCall", id).apply(); notifyCall(c.optString("handle")) }
        }
        val arr: JSONArray = res.optJSONArray("messages") ?: return
        val byConv = HashMap<String, MutableList<JSONObject>>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            byConv.getOrPut(m.getString("convId")) { mutableListOf() }.add(m)
        }
        for ((conv, list) in byConv) {
            val known = cur.has(conv) // first batch of a chat is only a baseline, not worth a notification
            val top = list.maxOf { it.optInt("serverSeq") }
            val others = list.filter { it.optString("senderId") != me && it.optString("type") != "react" }
            if (known && others.isNotEmpty()) notifyConv(conv, names.optString(conv, "Ijmāʿ"), others)
            cur.put(conv, maxOf(cur.optInt(conv, 0), top))
        }
        p.edit().putString("ncur", cur.toString()).apply()
    }

    private fun label(m: JSONObject): String = when (m.optString("type")) {
        "voice" -> "Voice message"
        "image" -> "Photo"
        "file" -> "File"
        else -> m.optString("body")
    }

    private fun notifyCall(who: String) {
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "calls")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Incoming voice call")
            .setContentText(if (who.isEmpty()) "A member is calling you" else "$who is calling you")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setTimeoutAfter(45000)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(this).notify(7, n) } catch (_: SecurityException) {}
    }

    private fun notifyConv(conv: String, title: String, msgs: List<JSONObject>) {
        val last = msgs.last()
        val text = if (msgs.size == 1) last.optString("handle") + ": " + label(last) else "${msgs.size} new messages"
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "messages")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(this).notify(conv.hashCode(), n) } catch (_: SecurityException) {}
    }
}
