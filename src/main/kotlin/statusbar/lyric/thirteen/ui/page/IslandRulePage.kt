package statusbar.lyric.thirteen.ui.page

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import statusbar.lyric.thirteen.core.IslandRule
import statusbar.lyric.thirteen.core.StateController
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.getWindowSize
import top.yukonga.miuix.kmp.utils.overScrollVertical

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandRulePage(navController: NavController) {
    val pm = LocalContext.current.packageManager
    val context = LocalContext.current

    var configuredApps by remember { mutableStateOf<List<AppRuleItem>>(emptyList()) }
    var showAppSelector by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<AppRuleItem?>(null) }

    LaunchedEffect(Unit) {
        configuredApps = loadConfiguredApps(context, pm)
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = MiuixTheme.colorScheme.background,
        tint = HazeTint(
            MiuixTheme.colorScheme.background.copy(
                if (scrollBehavior.state.collapsedFraction <= 0f) 1f
                else lerp(1f, 0.67f, scrollBehavior.state.collapsedFraction)
            )
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                modifier = Modifier
                    .hazeEffect(hazeState) {
                        style = hazeStyle
                        blurRadius = 25.dp
                        noiseFactor = 0f
                    }
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Right))
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Right))
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .windowInsetsPadding(WindowInsets.captionBar.only(WindowInsetsSides.Top)),
                title = "超级岛应用规则",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(
                        modifier = Modifier.padding(start = 20.dp),
                        onClick = { navController.popBackStack() }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Useful.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                },
                defaultWindowInsetsPadding = false
            )
        },
        popupHost = { null }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .hazeSource(state = hazeState)
                .height(getWindowSize().height.dp)
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Right))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Right)),
            contentPadding = paddingValues,
            overscrollEffect = null
        ) {
            item {
                SmallTitle(text = "配置每个 App 的超级岛显示行为。未配置的 App 遵循默认规则。")
            }

            if (configuredApps.isNotEmpty()) {
                item { SmallTitle(text = "已配置的应用") }
                items(configuredApps) { appItem ->
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { editingApp = appItem },
                                    onLongClick = {
                                        StateController.setUserIslandRule(appItem.packageName, IslandRule.DEFAULT)
                                        configuredApps = configuredApps.filterNot { it.packageName == appItem.packageName }
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = appItem.icon.asImageBitmap(),
                                contentDescription = appItem.appName,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(text = appItem.appName, fontSize = 15.sp)
                                Text(
                                    text = appItem.rule.displayName,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    BasicComponent(
                        modifier = Modifier.fillMaxWidth(),
                        title = "添加应用规则",
                        summary = "长按已配置项可删除规则",
                        onClick = { showAppSelector = true }
                    )
                }
            }

            item {
                SmallTitle(text = "默认规则说明")
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    BasicComponent(title = "歌词来源 App 的岛", summary = "→ 自动隐藏到挖孔")
                    BasicComponent(title = "视频类 App（B站/抖音等）", summary = "→ 直接拦截")
                    BasicComponent(title = "其他 App", summary = "→ 正常显示，系统接管")
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    // App 选择对话框
    val showAppSelectorState = remember { mutableStateOf(false) }
    LaunchedEffect(showAppSelector) { showAppSelectorState.value = showAppSelector }

    if (showAppSelector) {
        AppSelectorDialog(
            pm = pm,
            showState = showAppSelectorState,
            onAppSelected = { appItem ->
                configuredApps = (configuredApps + appItem).distinctBy { it.packageName }
                editingApp = appItem
                showAppSelector = false
            },
            onDismiss = { showAppSelector = false }
        )
    }

    // 规则选择对话框
    val showEditState = remember { mutableStateOf(false) }
    LaunchedEffect(editingApp) { showEditState.value = editingApp != null }

    if (editingApp != null) {
        IslandRuleSelectionDialog(
            appItem = editingApp!!,
            showState = showEditState,
            onRuleSelected = { rule ->
                StateController.setUserIslandRule(editingApp!!.packageName, rule)
                configuredApps = configuredApps.map {
                    if (it.packageName == editingApp!!.packageName) it.copy(rule = rule) else it
                }
                editingApp = null
            },
            onDismiss = { editingApp = null }
        )
    }
}

@Composable
private fun AppSelectorDialog(
    pm: PackageManager,
    showState: MutableState<Boolean>,
    onAppSelected: (AppRuleItem) -> Unit,
    onDismiss: () -> Unit
) {
    val apps = remember { mutableStateOf<List<AppRuleItem>>(emptyList()) }
    val searchQuery = remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps.value = loadInstalledApps(pm) }

    val filteredApps = apps.value.filter {
        it.appName.contains(searchQuery.value, ignoreCase = true) ||
            it.packageName.contains(searchQuery.value, ignoreCase = true)
    }

    SuperDialog(
        title = "选择应用",
        show = showState,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = searchQuery.value,
                onValueChange = { searchQuery.value = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                maxLines = 1
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                items(filteredApps) { appItem ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onAppSelected(appItem) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = appItem.icon.asImageBitmap(),
                            contentDescription = appItem.appName,
                            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(text = appItem.appName, fontSize = 14.sp)
                            Text(
                                text = appItem.packageName,
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = "关闭",
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun IslandRuleSelectionDialog(
    appItem: AppRuleItem,
    showState: MutableState<Boolean>,
    onRuleSelected: (IslandRule) -> Unit,
    onDismiss: () -> Unit
) {
    SuperDialog(
        title = "${appItem.appName} - 选择规则",
        show = showState,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            IslandRule.entries.forEach { rule ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onRuleSelected(rule) }
                        .background(
                            if (appItem.rule == rule)
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = rule.displayName, fontSize = 14.sp)
                        Text(
                            text = rule.description,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = "取消",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onDismiss
            )
        }
    }
}

data class AppRuleItem(
    val appName: String,
    val packageName: String,
    val icon: android.graphics.Bitmap,
    val rule: IslandRule
)

private suspend fun loadConfiguredApps(
    context: android.content.Context,
    pm: PackageManager
): List<AppRuleItem> = withContext(Dispatchers.IO) {
    val sp = context.getSharedPreferences("lyric_island_rules", android.content.Context.MODE_PRIVATE)
    val allRules = sp.all
    allRules.mapNotNull { (key, value) ->
        if (!key.startsWith("rule_")) return@mapNotNull null
        val packageName = key.removePrefix("rule_")
        val ruleName = value as? String ?: return@mapNotNull null
        try {
            val rule = IslandRule.valueOf(ruleName)
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val appName = pm.getApplicationLabel(appInfo).toString()
            val icon = appInfo.loadIcon(pm)?.let {
                (it as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            } ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            AppRuleItem(appName = appName, packageName = packageName, icon = icon, rule = rule)
        } catch (e: Exception) { null }
    }
}

private suspend fun loadInstalledApps(pm: PackageManager): List<AppRuleItem> = withContext(Dispatchers.IO) {
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        .map { appInfo ->
            val appName = pm.getApplicationLabel(appInfo).toString()
            val icon = appInfo.loadIcon(pm)?.let {
                (it as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    ?: android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
            } ?: android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
            AppRuleItem(appName = appName, packageName = appInfo.packageName, icon = icon, rule = IslandRule.DEFAULT)
        }
}
