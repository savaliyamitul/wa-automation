package com.nil.wasched

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Scheduler {
    private fun pi(c: Context, id: Long) = PendingIntent.getBroadcast(
        c, id.toInt(), Intent(c, AlarmReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun cancel(c: Context, id: Long) {
        c.getSystemService(AlarmManager::class.java).cancel(pi(c, id))
    }

    fun schedule(c: Context, m: Msg) {
        cancel(c, m.id)
        if (m.status != "PENDING" || m.dateTime <= System.currentTimeMillis()) return
        val am = c.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, m.dateTime, pi(c, m.id))
        else
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, m.dateTime, pi(c, m.id))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1)
        if (id < 0) return
        val svc = WaService.instance
        if (svc != null) { svc.enqueue(id); return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            Db.get(c).msgs().setStatus(id, "FAILED") // accessibility service not running
            pending.finish()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val dao = Db.get(c).msgs()
            dao.list().filter { it.status == "PENDING" }.forEach {
                if (it.dateTime <= System.currentTimeMillis()) dao.setStatus(it.id, "MISSED")
                else Scheduler.schedule(c, it)
            }
            pending.finish()
        }
    }
}
