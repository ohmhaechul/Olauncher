package app.olauncher.listener

import android.app.Activity
import android.os.Bundle
import app.olauncher.helper.DevicePolicyHelper

/**
 * Android 10+ Setup Wizard 프로비저닝 완료 직전 정책을 적용하고 완료(RESULT_OK)를 보고하는 액티비티
 */
class AdminPolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 피쳐폰 정책 및 락다운 적용
        DevicePolicyHelper.applyStudentKioskPolicies(this)
        setResult(Activity.RESULT_OK)
        finish()
    }
}
