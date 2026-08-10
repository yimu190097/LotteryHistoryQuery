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
 * 后台管理主页：标签页（仪表盘 / 用户管理 / 操作日志 / 系统设置）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabs: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    private val dateShortFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs = findViewById(R.id.tabs)
        contentFrame = findViewById(R.id.contentFrame)

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
    private var currentUserData: List<AdminApi.UserInfo> = emptyList()

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
                    currentUserData = data.data
                    userList.removeAllViews()
                    val now = System.currentTimeMillis()
                    data.data.forEach { u ->
                        val item = layoutInflater.inflate(R.layout.item_user, userList, false)
                        item.findViewById<TextView>(R.id.tvPhone).text = u.phone
                        item.findViewById<TextView>(R.id.tvNickname).text = u.nickname ?: "—"

                        val isMonthly = u.plan_type == "MONTHLY"
                        val isExpired = isMonthly && u.monthly_expire_at != null && u.monthly_expire_at < now

                        val planBadge = item.findViewById<TextView>(R.id.tvPlan)
                        planBadge.text = if (isMonthly) "月租" else "按次"
                        if (isMonthly) {
                            planBadge.setBackgroundColor(0xFFE8F5E9.toInt())
                            planBadge.setTextColor(0xFF2E7D32.toInt())
                        } else {
                            planBadge.setBackgroundColor(0xFFE3F2FD.toInt())
                            planBadge.setTextColor(0xFF1565C0.toInt())
                        }

                        val statusBadge = item.findViewById<TextView>(R.id.tvStatus)
                        if (isMonthly) {
                            statusBadge.visibility = View.VISIBLE
                            if (isExpired) {
                                statusBadge.text = "已过期"
                                statusBadge.setBackgroundColor(0xFFFFEBEE.toInt())
                                statusBadge.setTextColor(0xFFC62828.toInt())
                            } else {
                                statusBadge.text = "正常"
                                statusBadge.setBackgroundColor(0xFFE8F5E9.toInt())
                                statusBadge.setTextColor(0xFF2E7D32.toInt())
                            }
                        } else {
                            statusBadge.visibility = View.GONE
                        }

                        val expireText = item.findViewById<TextView>(R.id.tvExpire)
                        if (isMonthly && u.monthly_expire_at != null) {
                            expireText.visibility = View.VISIBLE
                            expireText.text = "到期: ${dateShortFmt.format(Date(u.monthly_expire_at))}"
                        } else {
                            expireText.visibility = View.GONE
                        }

                        item.findViewById<TextView>(R.id.tvQuota).text = "${u.remaining_queries ?: 0}"
                        val quotaColor = if ((u.remaining_queries ?: 0) > 0) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
                        item.findViewById<TextView>(R.id.tvQuota).setTextColor(quotaColor)

                        item.findViewById<TextView>(R.id.tvCreated).text = "注册: ${dateFmt.format(Date(u.created_at))}"

                        // 点击弹出操作菜单
                        item.setOnClickListener { showUserActionsDialog(u.phone) }
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

    // ==================== 用户操作菜单 ====================
    private fun showUserActionsDialog(phone: String) {
        val actions = arrayOf("查看详情", "设置配额", "重置密码", "删除用户")
        AlertDialog.Builder(this)
            .setTitle("操作 - $phone")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showUserDetail(phone)
                    1 -> showQuotaDialog(phone)
                    2 -> showResetPasswordDialog(phone)
                    3 -> confirmDeleteUser(phone)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 用户详情 ====================
    private fun showUserDetail(phone: String) {
        lifecycleScope.launch {
            try {
                val data = withContext(Dispatchers.IO) { AdminApi.getUserDetail(phone) }
                @Suppress("UNCHECKED_CAST")
                val u = data["user"] as? Map<String, Any> ?: return@launch
                val isMonthly = u["plan_type"] == "MONTHLY"
                val expireAt = u["monthly_expire_at"] as? Long
                val isExpired = isMonthly && expireAt != null && expireAt < System.currentTimeMillis()
                val createdAt = u["created_at"] as? Long
                val nickname = u["nickname"] as? String

                val detail = buildString {
                    append("手机号: ${u["phone"]}\n")
                    append("昵称: ${nickname ?: "—"}\n")
                    append("套餐类型: ${if (isMonthly) "月租用户" else "按次用户"}\n")
                    append("状态: ${if (isMonthly) (if (isExpired) "已过期" else "正常") else "—"}\n")
                    append("剩余次数: ${u["remaining_queries"] ?: 0}\n")
                    append("月租到期: ${if (expireAt != null) dateFmt.format(Date(expireAt)) else "—"}\n")
                    append("注册时间: ${if (createdAt != null) dateFmt.format(Date(createdAt)) else "—"}\n")
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("用户详情")
                    .setMessage(detail)
                    .setPositiveButton("修改配额") { _, _ -> showQuotaDialog(phone) }
                    .setNeutralButton("重置密码") { _, _ -> showResetPasswordDialog(phone) }
                    .setNegativeButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 配额设置（预填当前数据） ====================
    private fun showQuotaDialog(phone: String) {
        lifecycleScope.launch {
            var currentPlanType = "PAY_PER_USE"
            var currentQuota = 10
            var currentExpireAt: Long? = null

            try {
                val data = withContext(Dispatchers.IO) { AdminApi.getUserDetail(phone) }
                @Suppress("UNCHECKED_CAST")
                val u = data["user"] as? Map<String, Any>
                if (u != null) {
                    currentPlanType = u["plan_type"] as? String ?: "PAY_PER_USE"
                    currentQuota = (u["remaining_queries"] as? Number)?.toInt() ?: 10
                    currentExpireAt = (u["monthly_expire_at"] as? Number)?.toLong()
                }
            } catch (_: Exception) {}

            val planTypes = arrayOf("按次付费", "月租用户")
            val editQuota = EditText(this@MainActivity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(currentQuota.toString())
            }
            val editExpireDate = EditText(this@MainActivity).apply {
                hint = "yyyy-MM-dd"
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                if (currentExpireAt != null) {
                    setText(dateShortFmt.format(Date(currentExpireAt)))
                }
            }
            val spinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, planTypes)
                setSelection(if (currentPlanType == "MONTHLY") 1 else 0)
            }

            val layout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 0)
            }
            layout.addView(TextView(this@MainActivity).apply { text = "套餐类型"; textSize = 14f; setPadding(0, 8, 0, 4) })
            layout.addView(spinner)
            layout.addView(TextView(this@MainActivity).apply { text = "剩余次数"; textSize = 14f; setPadding(0, 16, 0, 4) })
            layout.addView(editQuota)
            layout.addView(TextView(this@MainActivity).apply { text = "月租到期（仅月租）"; textSize = 14f; setPadding(0, 16, 0, 4) })
            layout.addView(editExpireDate)

            AlertDialog.Builder(this@MainActivity)
                .setTitle("设置配额 - $phone")
                .setView(layout)
                .setPositiveButton("保存") { _, _ ->
                    val planType = if (spinner.selectedItemPosition == 0) "PAY_PER_USE" else "MONTHLY"
                    val remaining = editQuota.text.toString().toIntOrNull() ?: 10
                    var expireAt: Long? = null
                    if (planType == "MONTHLY") {
                        val dateStr = editExpireDate.text.toString().trim()
                        if (dateStr.isNotEmpty()) {
                            try {
                                expireAt = dateShortFmt.parse(dateStr)?.time
                            } catch (_: Exception) {}
                        }
                        if (expireAt == null) {
                            expireAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                        }
                    }
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                AdminApi.setQuota(phone, planType, remaining, expireAt)
                            }
                            Toast.makeText(this@MainActivity, "配额设置成功", Toast.LENGTH_SHORT).show()
                            switchTab(1)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ==================== 注册用户 ====================
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
            setText(dateShortFmt.format(Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)))
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
                            expireAt = dateShortFmt.parse(dateStr)?.time
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

    // ==================== 重置密码 ====================
    private fun showResetPasswordDialog(phone: String) {
        val editPassword = EditText(this).apply {
            hint = "新密码（至少6位）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val editPassword2 = EditText(this).apply {
            hint = "确认新密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 0)
        }
        layout.addView(TextView(this).apply { text = "新密码"; textSize = 14f; setPadding(0, 8, 0, 4) })
        layout.addView(editPassword)
        layout.addView(TextView(this).apply { text = "确认密码"; textSize = 14f; setPadding(0, 16, 0, 4) })
        layout.addView(editPassword2)

        AlertDialog.Builder(this)
            .setTitle("重置密码 - $phone")
            .setView(layout)
            .setPositiveButton("确认重置") { _, _ ->
                val p1 = editPassword.text.toString()
                val p2 = editPassword2.text.toString()
                if (p1.length < 6) {
                    Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (p1 != p2) {
                    Toast.makeText(this, "两次输入的密码不一致", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { AdminApi.resetPassword(phone, p1) }
                        Toast.makeText(this@MainActivity, "密码重置成功", Toast.LENGTH_SHORT).show()
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

    // ==================== 删除用户 ====================
    private fun confirmDeleteUser(phone: String) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除用户 $phone 吗？\n此操作不可恢复，将同时删除该用户的配额和同步数据。")
            .setPositiveButton("确认删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { AdminApi.deleteUser(phone) }
                        Toast.makeText(this@MainActivity, "用户已删除", Toast.LENGTH_SHORT).show()
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