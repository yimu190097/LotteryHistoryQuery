package com.lottery.admin.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lottery.admin.databinding.ActivityLoginBinding
import com.lottery.admin.network.AdminApi
import com.lottery.admin.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理员登录页
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString()
            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请输入用户名和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            doLogin(username, password)
        }
    }

    private fun doLogin(username: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "登录中..."
        lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    AdminApi.login(username, password)
                }
                AdminApi.token = resp.token
                // 保存登录态
                getSharedPreferences("admin", MODE_PRIVATE).edit()
                    .putString("token", resp.token)
                    .putString("username", resp.admin.username)
                    .putString("role", resp.admin.role)
                    .apply()
                startActivity(android.content.Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: ApiException) {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = "登 录"
                Toast.makeText(this@LoginActivity, e.message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = "登 录"
                Toast.makeText(this@LoginActivity, "网络错误: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}