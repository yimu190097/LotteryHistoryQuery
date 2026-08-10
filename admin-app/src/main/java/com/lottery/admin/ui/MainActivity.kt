package com.lottery.admin.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lottery.admin.R
import com.lottery.admin.network.AdminApi
import com.lottery.admin.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台管理主页：ViewPager 多页（仪表盘 / 用户管理 / 操作日志 / 系统设置）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tabs)
        contentFrame = findViewById(R.id.contentFrame)

        // 设置标签切换
        for (i in 0 until tabs.childCount) {
            tabs.getChildAt(i).setOnClickListener { switchTab(i) }
        }
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        for (i in 0 until tabs.childCount) {
            tabs.getChildAt(i).isSelected = (i == index)
        }
        contentFrame.removeAllViews()
        when (index) {
            0 -> loadDashboard()
            1 -> loadUsers()
            2 -> loadAuditLog()
            3 -> loadSettings()
        }
    }

    // ==================== 仪表盘 ====================
    private fun loadDashboard() {
        val view = layoutInflater.inflate(R.layout.page_dashboard, contentFrame, false)
        contentFrame.addView(view)

        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { AdminApi.getDashboard() }
                val s = data.stats
                view.findViewById<TextView>(R.id.tvTotalUsers).text = "${s.totalUsers}"
                view.findViewById<TextView>(R.id.tvTodayUsers).text = "${s.todayNewUsers}"
                view.findViewById<TextView>(R.id.tvTotalQueries).text = "${s.totalQueries}"
                view.findViewById<TextView>(R.id.tvTodayQueries).text = "${s.todayQueries}"

                // 日志列表
                val logContainer = view.findViewById<LinearLayout>(R.id.llLogs)
                logContainer.removeAllViews()
                data.recentLogs.take(20).forEach { log ->
                    val item = TextView(this@MainActivity).apply {
                        text = "${dateFmt.format(Date(log.created_at))}  ${log.admin_username ?: "—"}  ${log.action}"
                        setTextColor(0xFF424242.toInt())
                        textSize = 14f
                        setPadding(0, 8, 0, 8)
                    }
                    logContainer.addView(item)
                    // 分隔线
                    val div = View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                        setBackgroundColor(0xFFEEEEEE.toInt())
                    }
                    logContainer.addView(div)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 用户管理 ====================
    private var userPage = 1
    private fun loadUsers() {
        val view = layoutInflater.inflate(R.layout.page_users, contentFrame, false)
        contentFrame.addView(view)

        val userList = view.findViewById<LinearLayout>(R.id.llUserList)
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        val btnSearch = view.findViewById<Button>(R.id.btnSearch)
        val tvPage = view.findViewById<TextView>(R.id.tvPage)

        fun load(page: Int) {
            lifecycleScope.launch {
                try {
                    val data = withContext(Dispatchers.IO) {
                        AdminApi.getUsers(page, etSearch.text.toString().trim())
                    }
                    userList.removeAllViews()
                    data.data.forEach { u ->
                        val item = layoutInflater.inflate(R.layout.item_user, userList, false)
                        item.findViewById<TextView>(R.id.tvPhone).text = u.phone
                        item.findViewById<TextView>(R.id.tvNickname).text = u.nickname ?: "—"
                        val planBadge = item.findViewById<TextView>(R.id.tvPlan)
                        planBadge.text = if (u.plan_type == "MONTHLY") "月租" else "按次"
                        planBadge.setTextColor(if (u.plan_type == "MONTHLY") 0xFF2E7D32.toInt() else 0xFF1565C0.toInt())
                        item.findViewById<TextView>(R.id.tvQuota).text = "剩余${u.remaining_queries ?: 0}次"
                        item.findViewById<TextView>(R.id.tvCreated).text = dateFmt.format(Date(u.created_at))
                        item.setOnClickListener { showQuotaDialog(u.phone) }
                        userList.addView(item)
                    }
                    tvPage.text = "第${data.page}页 / 共${data.totalPages}页"
                    userPage = data.page
                } catch (e: ApiException) {
                    Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSearch.setOnClickListener { load(1) }
        view.findViewById<Button>(R.id.btnPrev).setOnClickListener { if (userPage > 1) load(userPage - 1) }
        view.findViewById<Button>(R.id.btnNext).setOnClickListener { load(userPage + 1) }
        view.findViewById<Button>(R.id.btnRegister).setOnClickListener { showRegisterDialog() }
        load(1)
    }

    private fun showQuotaDialog(phone: String) {
        val planTypes = arrayOf("按次付费", "月租用户")
        val editQuota = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("10") }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, planTypes)
        }
        layout.addView(TextView(this).apply { text = "套餐类型"; textSize = 14f; setPadding(0, 8, 0, 4) })
        layout.addView(spinner)
        layout.addView(TextView(this).apply { text = "剩余次数"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(editQuota)

        AlertDialog.Builder(this)
            .setTitle("设置配额 - $phone")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val planType = if (spinner.selectedItemPosition == 0) "PAY_PER_USE" else "MONTHLY"
                val remaining = editQuota.text.toString().toIntOrNull() ?: 10
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AdminApi.setQuota(phone, planType, remaining, null)
                        }
                        Toast.makeText(this@MainActivity, "配额设置成功", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRegisterDialog() {
        val planTypes = arrayOf("按次付费", "月租用户")
        val editPhone = EditText(this).apply {
            hint = "手机号"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val editPassword = EditText(this).apply {
            hint = "密码（至少6位）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val editNickname = EditText(this).apply { hint = "昵称（选填）" }
        val editQuota = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("10")
        }
        val editExpireDate = EditText(this).apply {
            hint = "到期日期 yyyy-MM-dd"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, planTypes)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        layout.addView(TextView(this).apply { text = "手机号 *"; textSize = 14f; setPadding(0, 8, 0, 4) })
        layout.addView(editPhone)
        layout.addView(TextView(this).apply { text = "密码 *"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(editPassword)
        layout.addView(TextView(this).apply { text = "昵称"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(editNickname)
        layout.addView(TextView(this).apply { text = "套餐类型"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(spinner)
        layout.addView(TextView(this).apply { text = "初始查询次数"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(editQuota)
        layout.addView(TextView(this).apply { text = "月租到期时间（仅月租用户）"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(editExpireDate)

        AlertDialog.Builder(this)
            .setTitle("注册新用户")
            .setView(layout)
            .setPositiveButton("注册") { _, _ ->
                val phone = editPhone.text.toString().trim()
                val password = editPassword.text.toString()
                if (phone.isEmpty() || password.length < 6) {
                    Toast.makeText(this, "手机号不能为空，密码至少6位", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val planType = if (spinner.selectedItemPosition == 0) "PAY_PER_USE" else "MONTHLY"
                val remaining = editQuota.text.toString().toIntOrNull() ?: 10
                val nickname = editNickname.text.toString().trim().ifBlank { null }
                var expireAt: Long? = null
                if (planType == "MONTHLY") {
                    val dateStr = editExpireDate.text.toString().trim()
                    if (dateStr.isNotEmpty()) {
                        try {
                            expireAt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(dateStr)?.time
                        } catch (_: Exception) {}
                    }
                    if (expireAt == null) {
                        expireAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                    }
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AdminApi.registerUser(phone, password, nickname, planType, remaining, expireAt)
                        }
                        Toast.makeText(this@MainActivity, "注册成功: $phone", Toast.LENGTH_SHORT).show()
                        // 刷新用户列表
                        switchTab(1)
                    } catch (e: ApiException) {
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 操作日志 ====================
    private var auditPage = 1
    private fun loadAuditLog() {
        val view = layoutInflater.inflate(R.layout.page_audit, contentFrame, false)
        contentFrame.addView(view)

        val logList = view.findViewById<LinearLayout>(R.id.llAuditList)
        val tvPage = view.findViewById<TextView>(R.id.tvPage)

        fun load(page: Int) {
            lifecycleScope.launch {
                try {
                    val data = withContext(Dispatchers.IO) { AdminApi.getAuditLog(page) }
                    logList.removeAllViews()
                    data.data.forEach { log ->
                        val item = layoutInflater.inflate(R.layout.item_audit, logList, false)
                        item.findViewById<TextView>(R.id.tvTime).text = dateFmt.format(Date(log.created_at))
                        item.findViewById<TextView>(R.id.tvAdmin).text = log.admin_username ?: "—"
                        item.findViewById<TextView>(R.id.tvAction).text = log.action
                        item.findViewById<TextView>(R.id.tvTarget).text = log.target ?: "—"
                        logList.addView(item)
                    }
                    tvPage.text = "第${data.page}页 / 共${data.totalPages}页"
                    auditPage = data.page
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        view.findViewById<Button>(R.id.btnPrev).setOnClickListener { if (auditPage > 1) load(auditPage - 1) }
        view.findViewById<Button>(R.id.btnNext).setOnClickListener { load(auditPage + 1) }
        load(1)
    }

    // ==================== 系统设置 ====================
    private fun loadSettings() {
        val view = layoutInflater.inflate(R.layout.page_settings, contentFrame, false)
        contentFrame.addView(view)

        lifecycleScope.launch {
            try {
                val configs = withContext(Dispatchers.IO) { AdminApi.getConfig() }
                view.findViewById<EditText>(R.id.etAppVersion).setText(configs["app_version"] ?: "")
                view.findViewById<EditText>(R.id.etFreeQuota).setText(configs["free_quota"] ?: "")
                view.findViewById<EditText>(R.id.etQueryPrice).setText(configs["query_price"] ?: "")
                view.findViewById<EditText>(R.id.etMonthlyPrice).setText(configs["monthly_price"] ?: "")
            } catch (_: Exception) {}
        }

        view.findViewById<Button>(R.id.btnSaveConfig).setOnClickListener {
            lifecycleScope.launch {
                try {
                    saveConfig("app_version", view.findViewById<EditText>(R.id.etAppVersion).text.toString())
                    saveConfig("free_quota", view.findViewById<EditText>(R.id.etFreeQuota).text.toString())
                    saveConfig("query_price", view.findViewById<EditText>(R.id.etQueryPrice).text.toString())
                    saveConfig("monthly_price", view.findViewById<EditText>(R.id.etMonthlyPrice).text.toString())
                    Toast.makeText(this@MainActivity, "配置已保存", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<Button>(R.id.btnLogout).setOnClickListener {
            getSharedPreferences("admin", MODE_PRIVATE).edit().clear().apply()
            AdminApi.token = null
            startActivity(android.content.Intent(this@MainActivity, LoginActivity::class.java))
            finish()
        }
    }

    private suspend fun saveConfig(key: String, value: String) {
        withContext(Dispatchers.IO) { AdminApi.updateConfig(key, value) }
    }
}