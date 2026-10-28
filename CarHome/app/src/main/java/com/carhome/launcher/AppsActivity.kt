package com.carhome.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

/** Простая сетка всех приложений. Иконки грузятся лениво и кэшируются. */
class AppsActivity : Activity() {

    private class App(val label: String, val info: ResolveInfo) {
        var icon: Drawable? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Режим выбора музыкального приложения
        val pickMusic = intent.getBooleanExtra(EXTRA_PICK_MUSIC, false)
        if (pickMusic) findViewById<TextView>(R.id.appsTitle).setText(R.string.pick_music_app)

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { App(it.loadLabel(packageManager).toString(), it) }
            .sortedBy { it.label.lowercase() }

        val grid = findViewById<GridView>(R.id.appsGrid)
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val app = apps[position]
                val icon = app.icon ?: app.info.loadIcon(packageManager).also { app.icon = it }
                v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(icon)
                v.findViewById<TextView>(R.id.appLabel).text = app.label
                return v
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val ai = apps[position].info.activityInfo
            if (pickMusic) {
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString(MainActivity.KEY_MUSIC_APP, ai.packageName).apply()
                Toast.makeText(this, getString(R.string.music_app_saved, apps[position].label), Toast.LENGTH_SHORT).show()
            }
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(launch)
            finish()
        }
    }

    companion object {
        const val EXTRA_PICK_MUSIC = "pick_music"
    }
}
