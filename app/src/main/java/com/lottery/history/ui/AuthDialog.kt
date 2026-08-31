package com.lottery.history.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.lottery.history.R
import com.lottery.history.data.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号弹窗：支持三种模式
 * - LOGIN：登录（手机号+密码）
 * - REGISTER：注册并登录（手机号+密码）
 * - CHANGE_PASSWORD：修改密码（旧密码+新密码），仅登录态可用
 *
 * 点击手机号时弹出此 Dialog（登录态→修改密码；未登录→登录）。
 */
class AuthDialog(
    context: Context,
    private val scope: LifecycleCoroutineScope,
    private val initialMode: Mode,
    private val onSuccess: () -> Unit
) : Dialog(context) {

    enum class Mode { LOGIN, REGISTER, CHANGE_PASSWORD }

    private val authRepo = AuthRepository(context)
    private var currentMode = initialMode
    private var actionJob: Job? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private lateinit var etPhone: EditText
    private lateinit var etPassword: EditText
    private lateinit var etNewPassword: EditText
    private lateinit var tvError: TextView
    private lateinit var btnAction: TextView
    private lateinit var tvSwitch: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_auth)
        window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(Gravity.CENTER)
        val lp = window?.attributes
        lp?.horizontalMargin = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
        )
        window?.attributes = lp
        setCancelable(true)

        bindViews()
        applyMode()
    }

    private fun bindViews() {
        tvTitle = findViewById(R.id.tvAuthTitle)
        tvHint = findViewById(R.id.tvAuthHint)
        etPhone = findViewById(R.id.etPhone)
        etPassword = findViewById(R.id.etPassword)
        etNewPassword = findViewById(R.id.etNewPassword)
        tvError = findViewById(R.id.tvAuthError)
        btnAction = findViewById(R.id.btnAuthAction)
        tvSwitch = findViewById(R.id.tvAuthSwitch)

        btnAction.setOnClickListener { performAction() }
        tvSwitch.setOnClickListener {
            currentMode = if (currentMode == Mode.LOGIN) Mode.REGISTER else Mode.LOGIN
            applyMode()
        }
    }

    private fun applyMode() {
        tvError.visibility = View.GONE
        when (currentMode) {
            Mode.LOGIN -> {
                tvTitle.text = context.getString(R.string.login_title)
                tvHint.text = context.getString(R.string.tap_to_login)
                etPhone.visibility = View.VISIBLE
                etPassword.visibility = View.VISIBLE
                etNewPassword.visibility = View.GONE
                btnAction.text = context.getString(R.string.login)
                tvSwitch.visibility = View.VISIBLE
                tvSwitch.text = context.getString(R.string.switch_to_register)
            }
            Mode.REGISTER -> {
                tvTitle.text = context.getString(R.string.register_title)
                tvHint.text = "注册即享每日2次免费查询，开通VIP不限次数"
                etPhone.visibility = View.VISIBLE
                etPassword.visibility = View.VISIBLE
                etNewPassword.visibility = View.GONE
                btnAction.text = context.getString(R.string.register)
                tvSwitch.visibility = View.VISIBLE
                tvSwitch.text = context.getString(R.string.switch_to_login)
            }
            Mode.CHANGE_PASSWORD -> {
                tvTitle.text = context.getString(R.string.change_password_title)
                tvHint.text = context.getString(R.string.change_password)
                etPhone.visibility = View.GONE
                etPassword.visibility = View.VISIBLE
                etPassword.hint = context.getString(R.string.hint_password)
                etNewPassword.visibility = View.VISIBLE
                btnAction.text = context.getString(R.string.confirm_change)
                tvSwitch.visibility = View.GONE
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun performAction() {
        val phone = etPhone.text.toString().trim()
        val password = etPassword.text.toString()
        val newPassword = etNewPassword.text.toString()

        btnAction.isEnabled = false
        actionJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (currentMode) {
                    Mode.LOGIN -> authRepo.login(phone, password)
                    Mode.REGISTER -> authRepo.register(phone, password)
                    Mode.CHANGE_PASSWORD -> authRepo.changePassword(password, newPassword)
                }
            }
            // 协程完成时对话框可能已被关闭，检查状态避免操作已销毁的 UI
            if (!isShowing) return@launch
            btnAction.isEnabled = true
        result.fold(
            onSuccess = { notice ->
                // 展示终端踢除等提示
                if (!notice.isNullOrBlank()) {
                    Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
                }
                Toast.makeText(context, "操作成功", Toast.LENGTH_SHORT).show()
                onSuccess()
                dismiss()
            },
            onFailure = { e -> showError(e.message ?: "操作失败") }
        )
        }
    }

    override fun dismiss() {
        actionJob?.cancel()
        super.dismiss()
    }
}
