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

    const val PACKAGE_MORE_APPS = "app.olauncher.more_apps"

    data class ExtraAppItem(
        val label: String,
        val packageName: String
    )

    // 필수 피쳐폰 앱 카테고리별 패키지 후보군 (삼성 / 구글 기본 + 삼성페이)
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
        // 캘린더
        "com.samsung.android.calendar",
        "com.google.android.calendar",
        "com.android.calendar",
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
        // 삼성페이 / 삼성월렛 (기본 탑재)
        "com.samsung.android.spay",
        "com.samsung.android.spaymini",
        "com.samsung.android.spayfw",
        "com.samsung.android.rapproxy",
        // 자녀 위치 추적 및 기기 찾기 (홈 화면 노출 없이 백그라운드 필수 동작)
        "com.samsung.android.app.find",
        "com.samsung.android.fmm",
        "com.samsung.android.oneconnect",
        "com.google.android.apps.adm"
    )

    data class ManageableApp(
        val label: String,
        val packageName: String,
        val isAllowed: Boolean
    )

    fun isPackageAllowed(context: Context, packageName: String): Boolean {
        if (ALLOWED_USER_PACKAGES.contains(packageName) || packageName == context.packageName) {
            return true
        }
        val prefs = Prefs(context)
        return prefs.customAllowedPackages.contains(packageName) ||
               prefs.backgroundOnlyAllowedPackages.contains(packageName)
    }

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

            // 9. 화면 더블탭 잠금 기본 활성화
            prefs.lockModeOn = true

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

            // Olauncher 본인이거나 기본/동적 허용 앱은 유지
            if (isPackageAllowed(context, pkgName)) {
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

        // [핵심]: One UI 홈(com.sec.android.app.launcher)과 설정(com.android.settings)은
        // 시스템 필수 컴포넌트이므로 패키지 레벨에서 숨기지 않습니다 (부팅 hang 방지).
        try {
            dpm.setApplicationHidden(admin, "com.sec.android.app.launcher", false)
            dpm.setApplicationHidden(admin, "com.android.settings", false)
        } catch (e: Exception) {
            // Ignore
        }

        // 삼성 파인더 검색, Free(spage), 분할화면 트레이(appsedge), 엣지 서비스(cocktailbarservice) 은닉하여
        // 최근 앱 화면의 상단 검색 및 멀티윈도우(분할 화면) 앱 선택 트레이를 통한 우회 차단
        val searchBlockPackages = listOf(
            "com.samsung.android.app.galaxyfinder",
            "com.samsung.android.app.spage",
            "com.samsung.android.app.appsedge",
            "com.samsung.android.app.cocktailbarservice"
        )
        for (pkg in searchBlockPackages) {
            try {
                dpm.setApplicationHidden(admin, pkg, true)
                Log.d(TAG, "Hidden search package: $pkg")
            } catch (e: Exception) {
                // Ignore
            }
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
        val calendar = findFirstInstalledPackage(listOf("com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"))
        val clock = findFirstInstalledPackage(listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"))
        val camera = findFirstInstalledPackage(listOf("com.sec.android.app.camera", "com.android.camera"))
        val gallery = findFirstInstalledPackage(listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"))

        prefs.homeAppsNum = 7
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
        calendar?.let {
            prefs.appName4 = getAppName(it)
            prefs.appPackage4 = it
        }
        clock?.let {
            prefs.appName5 = getAppName(it)
            prefs.appPackage5 = it
        }
        camera?.let {
            prefs.appName6 = getAppName(it)
            prefs.appPackage6 = it
        }
        gallery?.let {
            prefs.appName7 = getAppName(it)
            prefs.appPackage7 = it
        }

        syncHomeApps(context)
    }

    fun getExtraAppsList(context: Context): List<ExtraAppItem> {
        val pm = context.packageManager
        val prefs = Prefs(context)
        val result = mutableListOf<ExtraAppItem>()

        // 1. 삼성페이 (Samsung Wallet) 확인
        val spayCandidates = listOf(
            "com.samsung.android.spay",
            "com.samsung.android.spaymini"
        )
        for (pkg in spayCandidates) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                result.add(ExtraAppItem(label, pkg))
                break
            } catch (e: Exception) {
                // 미설치
            }
        }

        // 2. 부모님이 추가 허용한 앱들
        for (pkg in prefs.customAllowedPackages) {
            if (result.any { it.packageName == pkg }) continue
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                result.add(ExtraAppItem(label, pkg))
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 삼성페이를 맨 위에 두고, 나머지는 가나다순 정렬
        return result.sortedWith(compareBy({ if (it.packageName.contains("spay")) 0 else 1 }, { it.label }))
    }

    fun syncHomeApps(context: Context) {
        val prefs = Prefs(context)
        val pm = context.packageManager

        fun findFirstInstalledPackage(candidates: List<String>): String? {
            for (pkg in candidates) {
                try {
                    pm.getApplicationInfo(pkg, 0)
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

        // 1~7번 슬롯에 이전 버전에서 저장되었던 PACKAGE_MORE_APPS 정리
        for (i in 1..7) {
            if (prefs.getAppPackage(i) == PACKAGE_MORE_APPS) {
                when (i) {
                    1 -> { prefs.appName1 = ""; prefs.appPackage1 = "" }
                    2 -> { prefs.appName2 = ""; prefs.appPackage2 = "" }
                    3 -> { prefs.appName3 = ""; prefs.appPackage3 = "" }
                    4 -> { prefs.appName4 = ""; prefs.appPackage4 = "" }
                    5 -> { prefs.appName5 = ""; prefs.appPackage5 = "" }
                    6 -> { prefs.appName6 = ""; prefs.appPackage6 = "" }
                    7 -> { prefs.appName7 = ""; prefs.appPackage7 = "" }
                }
            }
        }

        // 1~7번 기본 필수 앱을 항상 정확하게 제자리에 동기화 (기존 잔여 덮어쓰기)
        val dialer = findFirstInstalledPackage(listOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.phone"))
        val messaging = findFirstInstalledPackage(listOf("com.samsung.android.messaging", "com.google.android.apps.messaging"))
        val contacts = findFirstInstalledPackage(listOf("com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts"))
        val calendar = findFirstInstalledPackage(listOf("com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"))
        val clock = findFirstInstalledPackage(listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"))
        val camera = findFirstInstalledPackage(listOf("com.sec.android.app.camera", "com.android.camera"))
        val gallery = findFirstInstalledPackage(listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"))

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
        calendar?.let {
            prefs.appName4 = getAppName(it)
            prefs.appPackage4 = it
        }
        clock?.let {
            prefs.appName5 = getAppName(it)
            prefs.appPackage5 = it
        }
        camera?.let {
            prefs.appName6 = getAppName(it)
            prefs.appPackage6 = it
        }
        gallery?.let {
            prefs.appName7 = getAppName(it)
            prefs.appPackage7 = it
        }

        // 8번 기타 앱 슬롯 동기화
        val extraApps = getExtraAppsList(context)
        if (extraApps.isNotEmpty()) {
            prefs.appName8 = "기타 앱"
            prefs.appPackage8 = PACKAGE_MORE_APPS
            prefs.homeAppsNum = 8
        } else {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
            prefs.homeAppsNum = 7
        }
    }

    fun getAppLaunchIntent(context: Context, packageName: String): Intent? {
        val pm = context.packageManager

        // 1. 표준 안드로이드 런처 인텐트 시도
        val standardIntent = pm.getLaunchIntentForPackage(packageName)
        if (standardIntent != null) {
            return standardIntent
        }

        // 2. 특수 시스템 앱(삼성 날씨 등)을 위한 폴백 인텐트
        if (packageName == "com.sec.android.daemonapp") {
            try {
                return Intent().apply {
                    component = ComponentName("com.sec.android.daemonapp", "com.sec.android.daemonapp.app.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // 3. 일반적인 폴백 탐색: 패키지 내 액티비티 중 exported 메인 액티비티 탐색
        try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }
            val activities = pkgInfo.activities ?: return null

            val candidate = activities.firstOrNull {
                it.exported && (it.name.contains("MainActivity") || it.name.contains("Main") || it.name.contains("Launcher"))
            } ?: activities.firstOrNull { it.exported }

            if (candidate != null) {
                return Intent().apply {
                    component = ComponentName(packageName, candidate.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        return null
    }

    fun getManageableApps(context: Context): List<ManageableApp> {
        val pm = context.packageManager
        val prefs = Prefs(context)
        val customAllowed = prefs.customAllowedPackages

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            0
        }
        val resolveList = pm.queryIntentActivities(launcherIntent, flags)
        val result = mutableListOf<ManageableApp>()
        val seenPackages = mutableSetOf<String>()

        for (info in resolveList) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || pkg == "com.sec.android.app.launcher" || pkg == "com.android.settings" ||
                pkg == "com.samsung.android.app.galaxyfinder" || pkg == "com.samsung.android.app.spage") continue
            if (ALLOWED_USER_PACKAGES.contains(pkg)) continue // 기본 필수 앱(전화, 문자, 삼성페이 등)은 제외
            if (!seenPackages.add(pkg)) continue

            // 실제로 실행할 수 있는 액티비티가 전혀 없는 순수 백그라운드 서비스 패키지는 제외!
            if (getAppLaunchIntent(context, pkg) == null) continue

            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            result.add(
                ManageableApp(
                    label = label,
                    packageName = pkg,
                    isAllowed = customAllowed.contains(pkg)
                )
            )
        }
        return result.sortedBy { it.label }
    }

    fun setCustomAllowedPackages(context: Context, newAllowedSet: Set<String>) {
        val prefs = Prefs(context)
        val oldAllowed = prefs.customAllowedPackages
        prefs.customAllowedPackages = newAllowedSet

        if (isDeviceOwner(context)) {
            val dpm = getDpm(context)
            val admin = getAdminComponent(context)

            // 새로 허용된 앱 unhide
            for (pkg in newAllowedSet) {
                try {
                    dpm.setApplicationHidden(admin, pkg, false)
                    Log.d(TAG, "Unhid custom allowed package: $pkg")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unhide: $pkg", e)
                }
            }

            // 해제된 앱 다시 hide
            for (pkg in oldAllowed) {
                if (!newAllowedSet.contains(pkg)) {
                    try {
                        dpm.setApplicationHidden(admin, pkg, true)
                        Log.d(TAG, "Hid custom package: $pkg")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to hide: $pkg", e)
                    }
                }
            }
        }

        syncHomeApps(context)
    }

    fun getBackgroundManageableApps(context: Context): List<ManageableApp> {
        val pm = context.packageManager
        val prefs = Prefs(context)
        val bgAllowed = prefs.backgroundOnlyAllowedPackages

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            0
        }
        val resolveList = pm.queryIntentActivities(launcherIntent, flags)
        val result = mutableListOf<ManageableApp>()
        val seenPackages = mutableSetOf<String>()

        for (info in resolveList) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || pkg == "com.sec.android.app.launcher" || pkg == "com.android.settings" ||
                pkg == "com.samsung.android.app.galaxyfinder" || pkg == "com.samsung.android.app.spage" ||
                pkg == "com.samsung.android.app.appsedge" || pkg == "com.samsung.android.app.cocktailbarservice") continue
            if (ALLOWED_USER_PACKAGES.contains(pkg)) continue
            if (!seenPackages.add(pkg)) continue

            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            result.add(
                ManageableApp(
                    label = label,
                    packageName = pkg,
                    isAllowed = bgAllowed.contains(pkg)
                )
            )
        }
        return result.sortedBy { it.label }
    }

    fun setBackgroundOnlyAllowedPackages(context: Context, newAllowedSet: Set<String>) {
        val prefs = Prefs(context)
        val oldAllowed = prefs.backgroundOnlyAllowedPackages
        prefs.backgroundOnlyAllowedPackages = newAllowedSet

        if (isDeviceOwner(context)) {
            val dpm = getDpm(context)
            val admin = getAdminComponent(context)

            // 새로 허용된 앱 unhide
            for (pkg in newAllowedSet) {
                try {
                    dpm.setApplicationHidden(admin, pkg, false)
                    Log.d(TAG, "Unhid background allowed package: $pkg")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unhide background app: $pkg", e)
                }
            }

            // 해제된 앱 다시 hide (단, 다른 허용 목록에도 없는 경우에만)
            for (pkg in oldAllowed) {
                if (!newAllowedSet.contains(pkg) && !isPackageAllowed(context, pkg)) {
                    try {
                        dpm.setApplicationHidden(admin, pkg, true)
                        Log.d(TAG, "Hid background package: $pkg")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to hide background app: $pkg", e)
                    }
                }
            }
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
                dpm.setApplicationHidden(admin, "com.android.settings", false)
                dpm.setApplicationHidden(admin, "com.samsung.android.app.galaxyfinder", false)
                dpm.setApplicationHidden(admin, "com.samsung.android.app.spage", false)
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
