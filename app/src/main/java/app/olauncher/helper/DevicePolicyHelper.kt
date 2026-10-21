package app.olauncher.helper

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.UserManager
import android.util.Log
import app.olauncher.MainActivity
import app.olauncher.data.Prefs
import app.olauncher.listener.DeviceAdmin

object DevicePolicyHelper {
    private const val TAG = "DevicePolicyHelper"

    // 필수 피쳐폰 앱 카테고리별 패키지 후보군 (삼성 / 구글 기본)
    val ALLOWED_USER_PACKAGES = setOf(
        // 전화
        "com.samsung.android.dialer",
        "com.android.phone",
        "com.google.android.dialer",
        // 메시지
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        // 연락처
        "com.samsung.android.app.contacts",
        "com.android.contacts",
        "com.google.android.contacts",
        // 시계 / 알람
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        // 카메라
        "com.sec.android.app.camera",
        "com.android.camera",
        "com.google.android.GoogleCamera",
        // 갤러리
        "com.sec.android.gallery3d",
        "com.google.android.apps.photos",
        // 설정 (시스템 제어용 - Olauncher 내부에서 PIN 보호)
        "com.android.settings",
    )

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, DeviceAdmin::class.java)
    }

    fun getDpm(context: Context): DevicePolicyManager {
        return context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = getDpm(context)
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun applyStudentKioskPolicies(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.w(TAG, "Not device owner. Skipping kiosk policies.")
            return
        }

        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        val prefs = Prefs(context)

        try {
            // 1. Olauncher를 기본 런처로 강제 고정
            setDefaultLauncher(context)

            // 2. 안전한 User Restrictions 적용
            // [중요]: DISALLOW_DEBUGGING_FEATURES 와 DISALLOW_USB_FILE_TRANSFER 는
            // 테스트 및 유지보수(ADB 연결)를 위해 기본 적용에서 제외합니다.
            val restrictions = listOf(
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_UNINSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_SET_WALLPAPER
            )

            for (r in restrictions) {
                try {
                    dpm.addUserRestriction(admin, r)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply restriction: $r", e)
                }
            }

            // 3. 잠금화면 위젯만 차단 (부재중 전화/문자 알림은 보이도록 허용)
            try {
                dpm.setKeyguardDisabledFeatures(
                    admin,
                    DevicePolicyManager.KEYGUARD_DISABLE_WIDGETS_ALL
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to configure keyguard features", e)
            }

            // 4. 상태바(상단바) 내리기 차단 (알림창 및 퀵패널을 통한 설정 진입 원천 차단)
            prefs.statusBarBlocked = true
            try {
                dpm.setStatusBarDisabled(admin, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable status bar", e)
            }

            // 5. 시스템 전체 다크 모드(Night Mode) 강제 적용
            setSystemDarkMode(context)

            // 6. 안전한 패키지 숨김 적용 (런처 UI 앱만 안전하게 숨김)
            applyPackageRestrictions(context)

            // 7. 완전한 순수 블랙 배경화면 설정
            setPureBlackWallpaper(context)

            // 8. 런처 홈 화면 6개 앱 기본 구성
            setupDefaultHomeApps(context)

            prefs.studentKioskEnabled = true
            Log.i(TAG, "Successfully applied student kiosk policies safely!")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying kiosk policies", e)
        }
    }

    fun setSystemDarkMode(context: Context) {
        try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
            uiModeManager.nightMode = android.app.UiModeManager.MODE_NIGHT_YES
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            try {
                android.provider.Settings.Secure.putInt(context.contentResolver, "ui_night_mode", 2)
            } catch (e: Exception) {
                // Ignore
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set dark mode", e)
        }
    }

    fun setDefaultLauncher(context: Context) {
        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val launcherActivity = ComponentName(context, MainActivity::class.java)
        try {
            dpm.addPersistentPreferredActivity(admin, filter, launcherActivity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set default launcher", e)
        }
    }

    fun setPureBlackWallpaper(context: Context) {
        try {
            val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
            }
            val wallpaperManager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
                wallpaperManager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
            } else {
                wallpaperManager.setBitmap(bitmap)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set black wallpaper", e)
        }
    }

    /**
     * [핵심 개선]: 시스템 백그라운드 패키지는 절대 건드리지 않고,
     * 오직 '런처 화면에 아이콘이 뜨는 사용자용 앱(Launcher Apps)' 중에서만
     * 화이트리스트 외의 앱(브라우저, 플레이스토어, 유튜브, 게임 등)을 숨깁니다.
     * 이를 통해 부팅 멈춤(Bootloop)을 100% 원천 방지합니다.
     */
    fun applyPackageRestrictions(context: Context) {
        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        val pm = context.packageManager

        // 런처에 아이콘이 노출되는 사용자 액티비티만 조회
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(launcherIntent, 0)
        val myPackage = context.packageName

        for (resolveInfo in resolveList) {
            val pkgName = resolveInfo.activityInfo.packageName

            // Olauncher 본인이거나 허용된 6대 피쳐폰 기능(+설정)은 유지
            if (ALLOWED_USER_PACKAGES.contains(pkgName) || pkgName == myPackage) {
                try {
                    dpm.setApplicationHidden(admin, pkgName, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unhide allowed app: $pkgName", e)
                }
            } else {
                // 화면에 아이콘이 뜨는 일반 앱(Chrome, 삼성인터넷, Play스토어, 유튜브, SNS 등)만 숨김!
                try {
                    dpm.setApplicationHidden(admin, pkgName, true)
                    Log.d(TAG, "Safely hidden user launcher app: $pkgName")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not hide user app: $pkgName", e)
                }
            }
        }

        // [핵심] com.sec.android.app.launcher(삼성 One UI 홈)는 숨기지 않습니다.
        // 삼성 기기에서 이를 숨기면 Recent Apps(최근 앱 키/오버뷰 화면)가 비활성화됩니다.
        // Olauncher는 setDefaultLauncher(addPersistentPreferredActivity)를 통해 영구 기본 런처로 고정되므로,
        // com.sec.android.app.launcher와 com.android.settings는 명시적으로 unhidden 상태를 유지합니다.
        try {
            dpm.setApplicationHidden(admin, "com.sec.android.app.launcher", false)
        } catch (e: Exception) {
            // Ignore
        }
        try {
            dpm.setApplicationHidden(admin, "com.android.settings", false)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun setSettingsAppHidden(context: Context, hidden: Boolean) {
        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        try {
            dpm.setApplicationHidden(admin, "com.android.settings", hidden)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle settings hidden: $hidden", e)
        }
    }

    fun setAccountModificationAllowed(context: Context, allowed: Boolean) {
        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        try {
            if (allowed) {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            } else {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set account modification restriction", e)
        }
    }

    fun setStatusBarDisabled(context: Context, disabled: Boolean) {
        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        try {
            dpm.setStatusBarDisabled(admin, disabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle status bar disabled: $disabled", e)
        }
    }

    fun setupDefaultHomeApps(context: Context) {
        val prefs = Prefs(context)
        val pm = context.packageManager

        fun findFirstInstalledPackage(candidates: List<String>): String? {
            for (pkg in candidates) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return pkg
                } catch (e: Exception) {
                    // Not installed
                }
            }
            return null
        }

        fun getAppName(packageName: String): String {
            return try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
        }

        val dialer = findFirstInstalledPackage(listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.phone"))
        val messaging = findFirstInstalledPackage(listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"))
        val contacts = findFirstInstalledPackage(listOf("com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts"))
        val clock = findFirstInstalledPackage(listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"))
        val camera = findFirstInstalledPackage(listOf("com.sec.android.app.camera", "com.android.camera"))
        val gallery = findFirstInstalledPackage(listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"))

        prefs.homeAppsNum = 6
        dialer?.let {
            prefs.appName1 = getAppName(it)
            prefs.appPackage1 = it
        }
        messaging?.let {
            prefs.appName2 = getAppName(it)
            prefs.appPackage2 = it
        }
        contacts?.let {
            prefs.appName3 = getAppName(it)
            prefs.appPackage3 = it
        }
        clock?.let {
            prefs.appName4 = getAppName(it)
            prefs.appPackage4 = it
        }
        camera?.let {
            prefs.appName5 = getAppName(it)
            prefs.appPackage5 = it
        }
        gallery?.let {
            prefs.appName6 = getAppName(it)
            prefs.appPackage6 = it
        }
    }

    /**
     * 피쳐폰 정책을 해제하고 모든 앱과 시스템 설정을 정상 스마트폰으로 원상 복구합니다.
     */
    fun disableStudentKioskPolicies(context: Context) {
        if (!isDeviceOwner(context)) {
            Log.w(TAG, "Not device owner. Cannot disable kiosk policies.")
            return
        }

        val dpm = getDpm(context)
        val admin = getAdminComponent(context)
        val prefs = Prefs(context)

        try {
            // 1. 모든 User Restrictions 해제
            val restrictions = listOf(
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_UNINSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_REMOVE_USER,
                UserManager.DISALLOW_SET_WALLPAPER,
                UserManager.DISALLOW_CREATE_WINDOWS,
                UserManager.DISALLOW_MODIFY_ACCOUNTS
            )
            for (r in restrictions) {
                try {
                    dpm.clearUserRestriction(admin, r)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear restriction: $r", e)
                }
            }

            // 2. 상단바 잠금 해제
            try {
                dpm.setStatusBarDisabled(admin, false)
                prefs.statusBarBlocked = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-enable status bar", e)
            }

            // 3. 잠금화면 제약 해제
            try {
                dpm.setKeyguardDisabledFeatures(admin, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset keyguard features", e)
            }

            // 4. 숨겨졌던 모든 런처 앱 복구 (다시 표시)
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                0
            }
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveList = pm.queryIntentActivities(launcherIntent, flags)
            for (resolveInfo in resolveList) {
                val pkgName = resolveInfo.activityInfo.packageName
                try {
                    dpm.setApplicationHidden(admin, pkgName, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unhide app: $pkgName", e)
                }
            }

            // 제조사 기본 홈 런처 복구
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val homeList = pm.queryIntentActivities(homeIntent, flags)
            for (resolveInfo in homeList) {
                val pkgName = resolveInfo.activityInfo.packageName
                try {
                    dpm.setApplicationHidden(admin, pkgName, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unhide home launcher: $pkgName", e)
                }
            }
            try {
                dpm.setApplicationHidden(admin, "com.sec.android.app.launcher", false)
            } catch (e: Exception) {
                // Ignore
            }

            // 5. 기본 런처 고정 해제
            try {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear persistent preferred launcher", e)
            }

            prefs.studentKioskEnabled = false
            Log.i(TAG, "Successfully restored device to normal smartphone mode!")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling kiosk policies", e)
        }
    }
}
