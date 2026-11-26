
package com.pes2017.recomp

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Choosing which Vulkan the game talks to: the one the phone shipped with,
 * or a driver the player imported as a zip.
 */
class DriverActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: DriverStore
    private lateinit var current: TextView
    private lateinit var list: ListView
    private var drivers: List<DriverStore.Driver> = emptyList()

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        when (val outcome = store.import(uri, uri.lastPathSegment ?: "driver")) {
            is DriverStore.Outcome.Imported -> {
                Toast.makeText(
                    this, getString(R.string.driver_imported, outcome.driver.name), Toast.LENGTH_LONG
                ).show()
                choose(outcome.driver)
            }

            is DriverStore.Outcome.Failed -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.import_driver)
                    .setMessage(outcome.reason)
                    .setPositiveButton(R.string.close, null)
                    .show()
            }
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_driver)
        applySystemBars()

        findViewById<android.view.View>(R.id.back_button)
            .setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }

        prefs = Prefs(this)
        store = DriverStore(this)
        title = getString(R.string.graphics_driver)

        current = findViewById(R.id.driver_current)
        list = findViewById(R.id.driver_list)

        findViewById<Button>(R.id.import_driver).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/octet-stream", "*/*"),
            )
            pickZip.launch(intent)
        }

        findViewById<Button>(R.id.download_driver)
            .setOnClickListener { chooseDownload(it as Button) }

        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) chooseSystem() else choose(drivers[position - 1])
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position > 0) confirmRemove(drivers[position - 1])
            true
        }

        refresh()
    }

    private fun chooseDownload(button: Button) {
        val renderer = try {
            DeviceProfile.detect(this, prefs).specs.gpu
        } catch (_: Throwable) {
            ""
        }

        if (!DriverDownloader.suitable(renderer)) {
            Toast.makeText(this, R.string.driver_none_for_gpu, Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.download_driver)
            .setItems(
                arrayOf(
                    getString(R.string.driver_source_recommended),
                    getString(R.string.driver_source_purple)
                )
            ) { _, which ->
                downloadDriver(
                    button,
                    renderer,
                    DriverDownloader.Choice.values()[which]
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadDriver(
        button: Button,
        renderer: String,
        choice: DriverDownloader.Choice
    ) {
        button.isEnabled = false
        Toast.makeText(this, R.string.driver_downloading, Toast.LENGTH_SHORT).show()

        Thread {
            val result = DriverDownloader.download(this, renderer, store, choice)

            runOnUiThread {
                button.isEnabled = true

                when (result) {
                    is DriverDownloader.Result.Done -> {
                        choose(result.driver)
                        Toast.makeText(
                            this,
                            getString(R.string.driver_downloaded, result.driver.name),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    is DriverDownloader.Result.Failed ->
                        Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun refresh() {
        drivers = store.list()
        current.text = getString(R.string.driver_in_use, chosenName())
        list.adapter = Adapter()
    }

    private fun chosenName(): String {
        val directory = prefs.driverDirectory
        if (directory.isEmpty()) return getString(R.string.driver_system)

        return drivers.firstOrNull {
            it.directory.absolutePath == directory
        }?.name ?: getString(R.string.driver_system)
    }

    private fun chooseSystem() {
        prefs.driverDirectory = ""
        prefs.driverLibrary = ""

        if (NativeBridge.isLoaded()) {
            NativeBridge.nativeSelectDriver("", "")
        }

        refresh()
    }

    private fun choose(driver: DriverStore.Driver) {
        prefs.driverDirectory = driver.directory.absolutePath
        prefs.driverLibrary = driver.library

        if (NativeBridge.isLoaded()) {
            NativeBridge.nativeSelectDriver(
                driver.directory.absolutePath,
                driver.library
            )
        }

        refresh()
    }

    private fun confirmRemove(driver: DriverStore.Driver) {
        AlertDialog.Builder(this)
            .setTitle(driver.name)
            .setMessage(driver.description)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.driver_remove) { _, _ ->
                if (prefs.driverDirectory == driver.directory.absolutePath) {
                    chooseSystem()
                }

                store.remove(driver)

                Toast.makeText(
                    this,
                    R.string.driver_removed,
                    Toast.LENGTH_SHORT
                ).show()

                refresh()
            }
            .show()
    }

    /** The system's driver, then every imported one. */
    private inner class Adapter : BaseAdapter() {

        override fun getCount(): Int = drivers.size + 1

        override fun getItem(position: Int): Any =
            if (position == 0) "system" else drivers[position - 1]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup
        ): View {

            val row = (convertView as? LinearLayout)
                ?: LinearLayout(this@DriverActivity).apply {
                    orientation = LinearLayout.VERTICAL

                    val padding = (12 * resources.displayMetrics.density).roundToInt()
                    setPadding(padding, padding, padding, padding)

                    addView(
                        TextView(this@DriverActivity).apply {
                            id = android.R.id.text1
                            textSize = 16f
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    )

                    addView(
                        TextView(this@DriverActivity).apply {
                            id = android.R.id.text2
                            textSize = 12f
                            setPadding(0, (4 * resources.displayMetrics.density).roundToInt(), 0, 0)
                        }
                    )
                }

            val title = row.findViewById<TextView>(android.R.id.text1)
            val detail = row.findViewById<TextView>(android.R.id.text2)

            val chosenDirectory = prefs.driverDirectory

            val selected = if (position == 0) {
                chosenDirectory.isEmpty()
            } else {
                chosenDirectory == drivers[position - 1].directory.absolutePath
            }

            if (position == 0) {
                title.text = getString(R.string.driver_system)

                detail.text = if (NativeBridge.isLoaded()) {
                    try {
                        NativeBridge.nativeDriverDescription()
                    } catch (_: Throwable) {
                        ""
                    }
                } else ""
            } else {
                val driver = drivers[position - 1]

                title.text = driver.name

                detail.text = buildString {
                    if (driver.version.isNotEmpty()) append(driver.version)

                    if (driver.vendor.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(driver.vendor)
                    }

                    if (driver.author.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append(driver.author)
                    }

                    if (driver.description.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append(driver.description)
                    }
                }
            }

            // Xclipse Dark Edition: selected item gets a blue highlight.
            row.setBackgroundResource(
                if (selected) R.drawable.bg_entry_selected
                else R.drawable.bg_setting_row
            )

            row.alpha = 1f

            title.setTextColor(getColor(R.color.pes_text))
            detail.setTextColor(getColor(R.color.pes_muted))

            return row
        }
    }
}
