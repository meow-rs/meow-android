package io.github.madeye.meow.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.madeye.meow.AppGraph
import io.github.madeye.meow.BuildConfig
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.ui.components.MeowScaffold
import io.github.madeye.meow.ui.nav.Dest
import io.github.madeye.meow.ui.nav.TABS
import io.github.madeye.meow.ui.screens.connections.ConnectionsActions
import io.github.madeye.meow.ui.screens.connections.ConnectionsScreen
import io.github.madeye.meow.ui.screens.connections.ConnectionsViewModel
import io.github.madeye.meow.ui.screens.dns.DnsScreen
import io.github.madeye.meow.ui.screens.dns.DnsViewModel
import io.github.madeye.meow.ui.screens.home.ExitIpViewModel
import io.github.madeye.meow.ui.screens.home.HomeScreen
import io.github.madeye.meow.ui.screens.home.HomeViewModel
import io.github.madeye.meow.ui.screens.logs.LogsActions
import io.github.madeye.meow.ui.screens.logs.LogsScreen
import io.github.madeye.meow.ui.screens.logs.LogsViewModel
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyScreen
import io.github.madeye.meow.ui.screens.perapp.PerAppProxyViewModel
import io.github.madeye.meow.ui.screens.proxies.ProxyGroupsScreen
import io.github.madeye.meow.ui.screens.proxies.ProxyGroupsViewModel
import io.github.madeye.meow.ui.screens.rules.RulesScreen
import io.github.madeye.meow.ui.screens.rules.RulesViewModel
import io.github.madeye.meow.ui.screens.settings.SettingsEvent
import io.github.madeye.meow.ui.screens.settings.SettingsScreen
import io.github.madeye.meow.ui.screens.settings.SettingsViewModel
import io.github.madeye.meow.ui.screens.settings.UpdateDialog
import io.github.madeye.meow.ui.screens.subscribe.InstallConfigDialog
import io.github.madeye.meow.ui.screens.subscribe.InstallConfigLink
import io.github.madeye.meow.ui.screens.subscribe.ProfileUi
import io.github.madeye.meow.ui.screens.subscribe.SubscribeEvent
import io.github.madeye.meow.ui.screens.subscribe.SubscribeViewModel
import io.github.madeye.meow.ui.screens.subscribe.SubscriptionBusyOverlay
import io.github.madeye.meow.ui.screens.subscribe.SubscriptionDialog
import io.github.madeye.meow.ui.screens.subscribe.SubscriptionQrDialog
import io.github.madeye.meow.ui.screens.subscribe.SubscriptionScanActivity
import io.github.madeye.meow.ui.screens.subscribe.messageRes
import io.github.madeye.meow.ui.screens.subscribe.subscriptionItems
import io.github.madeye.meow.ui.screens.utility.UtilityScreen
import io.github.madeye.meow.ui.screens.yaml.YamlEditorActions
import io.github.madeye.meow.ui.screens.yaml.YamlEditorScreen
import io.github.madeye.meow.ui.screens.yaml.YamlEditorViewModel
import io.github.madeye.meow.ui.screens.yaml.rememberSoraEditorHandle
import io.github.madeye.meow.ui.theme.meow
import io.github.madeye.meow.ui.util.openPlayListing
import io.github.madeye.meow.ui.util.openUrl
import io.github.madeye.meow.ui.util.readText
import io.github.madeye.meow.ui.util.rememberClipboardText
import io.github.madeye.meow.ui.util.rememberNotificationPermissionRequest
import io.github.madeye.meow.ui.util.writeText
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Root of the Compose UI: four tabs plus the pushed detail screens.
 *
 * @param autoConnect set by the e2e harness via `--ez auto_connect true`.
 * @param installLink an install-config link the app was opened with, until the
 *   user has answered it; [onInstallLinkHandled] clears it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MeowApp(
    autoConnect: Boolean = false,
    installLink: InstallConfigLink? = null,
    onInstallLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val snackbarHost = remember { SnackbarHostState() }
    ProfileReloadNotice(snackbarHost)

    Box(
        // Exposes every Modifier.testTag as a resource-id in uiautomator dumps,
        // so e2e can wait on a stable id instead of localized text.
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        MeowNavHost(
            navController = navController,
            snackbarHost = snackbarHost,
            autoConnect = autoConnect,
            installLink = installLink,
            onInstallLinkHandled = onInstallLinkHandled,
        )
    }
}

/**
 * Says so when a profile change restarts the connected VPN, or when an update
 * fails the engine's check and the VPN keeps its current config. Profiles
 * change on several screens (and in the background), so this sits at the
 * root; the restart itself is `ConfigReloader`'s. Collected only while
 * started, and the flows do not replay, so nothing is announced late.
 */
@Composable
private fun ProfileReloadNotice(snackbarHost: SnackbarHostState) {
    val reconnecting = stringResource(R.string.home_reconnecting_profile)
    val rejected = stringResource(R.string.home_profile_rejected)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, reconnecting, rejected) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val reloader = AppGraph.configReloader
            merge(
                reloader.reloads.map { reconnecting },
                // The engine's error goes to the log only; it runs too long
                // for a snackbar.
                reloader.rejected.map { rejected },
            ).collect { message ->
                // Both only matter while the VPN is up: the broadcast is a
                // no-op otherwise, and a rejected update kept nothing from it.
                if (AppGraph.vpn.state.value == BaseService.State.Connected) {
                    snackbarHost.showSnackbar(message)
                }
            }
        }
    }
}

@Composable
private fun MeowNavHost(
    navController: NavHostController,
    snackbarHost: SnackbarHostState,
    autoConnect: Boolean,
    installLink: InstallConfigLink?,
    onInstallLinkHandled: () -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onTab = TABS.any { tab ->
        backStackEntry?.destination?.hierarchy?.any { it.hasRoute(tab.dest::class) } == true
    }

    NavHost(navController = navController, startDestination = Dest.Home) {
        composable<Dest.Home> {
            HomeRoute(
                snackbarHost = snackbarHost,
                autoConnect = autoConnect,
                installLink = installLink,
                onInstallLinkHandled = onInstallLinkHandled,
                onEditYaml = { navController.navigate(Dest.YamlEditor(it)) },
                bottomBar = { BottomBar(navController, onTab) },
            )
        }
        composable<Dest.ProxyGroups> {
            ProxyGroupsRoute(bottomBar = { BottomBar(navController, onTab) })
        }
        composable<Dest.Utility> {
            UtilityRoute(
                onTraffic = { navController.navigate(Dest.Traffic) },
                onConnections = { navController.navigate(Dest.Connections) },
                onLogs = { navController.navigate(Dest.Logs) },
                onDns = { navController.navigate(Dest.Dns) },
                bottomBar = { BottomBar(navController, onTab) },
            )
        }
        composable<Dest.Settings> {
            SettingsRoute(
                snackbarHost = snackbarHost,
                onPerAppProxy = { navController.navigate(Dest.PerAppProxy) },
                onRules = { navController.navigate(Dest.Rules) },
                bottomBar = { BottomBar(navController, onTab) },
            )
        }

        composable<Dest.PerAppProxy> { PerAppProxyRoute(onBack = navController::popBackStack) }
        composable<Dest.YamlEditor> { entry ->
            YamlEditorRoute(
                snackbarHost = snackbarHost,
                onBack = navController::popBackStack,
            )
        }
        composable<Dest.Traffic> { TrafficRoute(onBack = navController::popBackStack) }
        composable<Dest.Connections> { ConnectionsRoute(onBack = navController::popBackStack) }
        composable<Dest.Rules> { RulesRoute(onBack = navController::popBackStack) }
        composable<Dest.Logs> { LogsRoute(onBack = navController::popBackStack) }
        composable<Dest.Dns> { DnsRoute(onBack = navController::popBackStack) }
    }

    // A link is answered on Home, where the new subscription then shows up. If
    // Home is already open it is left alone, even under the YAML editor (only
    // reachable from it): switching tabs recreates the editor and would drop
    // unsaved edits, so the prompt waits until the user is back on the list.
    LaunchedEffect(installLink) {
        if (installLink == null) return@LaunchedEffect
        val current = navController.currentDestination
        val onHome = current?.hasRoute(Dest.Home::class) == true ||
            current?.hasRoute(Dest.YamlEditor::class) == true
        if (!onHome) navController.navigateToTab(Dest.Home)
    }
}

@Composable
private fun BottomBar(navController: NavHostController, visible: Boolean) {
    if (!visible) return
    val backStackEntry by navController.currentBackStackEntryAsState()
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        TABS.forEach { tab ->
            val selected = backStackEntry?.destination?.hierarchy
                ?.any { it.hasRoute(tab.dest::class) } == true
            NavigationBarItem(
                selected = selected,
                onClick = { navController.navigateToTab(tab.dest) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                modifier = Modifier.testTag(tab.testTag),
                // M3 defaults the selected pill to secondaryContainer, which is
                // the ginger brand accent here — selection has to read as the
                // blue accent, same as the iOS tab bar.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.meow.mutedText,
                    unselectedTextColor = MaterialTheme.meow.mutedText,
                ),
            )
        }
    }
}

private fun NavHostController.navigateToTab(dest: Dest) {
    navigate(dest) {
        // Restores each tab's saved scroll/search state, the equivalent of
        // Flutter's IndexedStack.
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// -----------------------------------------------------------------------------
// Routes
// -----------------------------------------------------------------------------

@Composable
private fun HomeRoute(
    snackbarHost: SnackbarHostState,
    autoConnect: Boolean,
    installLink: InstallConfigLink?,
    onInstallLinkHandled: () -> Unit,
    onEditYaml: (Long) -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val viewModel: HomeViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exitIp: ExitIpViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val exitIpState by exitIp.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val askForNotifications = rememberNotificationPermissionRequest()

    fun startVpn() {
        startVpnService(context)
        // After the start, never before: the VPN must not wait on this answer.
        askForNotifications()
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn()
    }

    fun connect() {
        viewModel.onConnectRequested()
        val intent = android.net.VpnService.prepare(context)
        if (intent == null) startVpn() else vpnPermission.launch(intent)
    }

    // The :vpn process can be killed while backgrounded, leaving stale state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    // Preserves the harness contract: launch with auto_connect and the VPN
    // starts once the service reports it has settled.
    var autoConnectHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoConnect, state.state) {
        if (autoConnect && !autoConnectHandled && state.state == BaseService.State.Stopped) {
            autoConnectHandled = true
            connect()
        }
    }

    SubscriptionsHost(
        snackbarHost = snackbarHost,
        installLink = installLink,
        onInstallLinkHandled = onInstallLinkHandled,
        onEditYaml = onEditYaml,
        bottomBar = bottomBar,
    ) { padding, subscriptions ->
        HomeScreen(
            state = state,
            contentPadding = padding,
            onToggle = { checked ->
                if (checked) connect() else viewModel.onDisconnect(context)
            },
            onSelectRouteMode = viewModel::onSelectRouteMode,
            exitIp = exitIpState,
            onRefreshExitIp = exitIp::refresh,
            subscriptions = subscriptions,
        )
    }
}

private fun startVpnService(context: android.content.Context) {
    // startForegroundService under the hood: the service answers its watchdog
    // with startForeground() on every onStartCommand path.
    io.github.madeye.meow.bg.VpnService.start(context)
}

/**
 * Home's scaffold, with everything the subscription list needs around it: the
 * refresh and add actions, the file and QR launchers, the dialogs. [content]
 * lays the list's items out under Home's own cards.
 */
@Composable
private fun SubscriptionsHost(
    snackbarHost: SnackbarHostState,
    installLink: InstallConfigLink?,
    onInstallLinkHandled: () -> Unit,
    onEditYaml: (Long) -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues, LazyListScope.() -> Unit) -> Unit,
) {
    val viewModel: SubscribeViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = rememberClipboardText()

    var dialogFor by remember { mutableStateOf<ProfileUi?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ProfileUi?>(null) }
    var qrFor by remember { mutableStateOf<ProfileUi?>(null) }
    // The raw text, so it survives recreation; parsed again below.
    var scanned by rememberSaveable { mutableStateOf<String?>(null) }
    val scannedLink = remember(scanned) {
        scanned?.let(InstallConfigLink::parseScanned) as? InstallConfigLink.Valid
    }

    // Hoisted out of the callbacks: resolving resources through LocalContext
    // inside a lambda skips Compose's configuration tracking.
    val clipboardEmpty = stringResource(R.string.subs_clipboard_empty)
    val exportSaved = stringResource(R.string.subs_export_saved)
    val exportFailedFmt = stringResource(R.string.subs_export_failed)
    val importedFmt = stringResource(R.string.subs_imported)
    val importFailedFmt = stringResource(R.string.subs_import_failed)
    val updatedFmt = stringResource(R.string.subs_updated)
    val refreshFailedFmt = stringResource(R.string.subs_refresh_failed)
    val linkRejected = (installLink as? InstallConfigLink.Invalid)
        ?.let { stringResource(it.reason.messageRes()) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = context.readText(uri)
            if (content != null) {
                viewModel.import(uri.lastPathSegment?.substringAfterLast('/') ?: "config", content)
            }
        }
    }

    // The scanner only returns codes parseScanned accepts.
    val scanLauncher = rememberLauncherForActivityResult(
        SubscriptionScanActivity.Contract(),
    ) { text -> if (text != null) scanned = text }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-yaml"),
    ) { uri ->
        val profile = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val yaml = viewModel.yamlOf(profile.id)
            if (context.writeText(uri, yaml)) {
                viewModel.onExported()
                snackbarHost.showSnackbar(exportSaved)
            } else {
                snackbarHost.showSnackbar(String.format(exportFailedFmt, profile.name))
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SubscribeEvent.Imported -> String.format(importedFmt, event.name)
                is SubscribeEvent.Updated -> String.format(updatedFmt, event.name)
                is SubscribeEvent.ImportFailed -> String.format(importFailedFmt, event.reason)
                is SubscribeEvent.RefreshFailed -> String.format(refreshFailedFmt, event.reason)
                is SubscribeEvent.Failure -> event.reason
            }
            snackbarHost.showSnackbar(message)
        }
    }

    // A rejected link has nothing to confirm: say why and drop it. The snackbar
    // runs in [scope] because clearing the link restarts this effect, which
    // would cancel it mid-display.
    LaunchedEffect(linkRejected) {
        if (linkRejected == null) return@LaunchedEffect
        onInstallLinkHandled()
        scope.launch { snackbarHost.showSnackbar(linkRejected) }
    }

    MeowScaffold(
        title = stringResource(R.string.app_name),
        bottomBar = bottomBar,
        actions = {
            IconButton(onClick = viewModel::refreshAll) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.common_refresh))
            }
            Box {
                IconButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.subs_add))
                }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_add_from_url)) },
                        onClick = {
                            addMenuOpen = false
                            dialogFor = null
                            dialogOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_scan_qr)) },
                        onClick = {
                            addMenuOpen = false
                            scanLauncher.launch(Unit)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.subs_import_from_file)) },
                        onClick = {
                            addMenuOpen = false
                            importLauncher.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(padding) {
                subscriptionItems(
                    state = state,
                    onSelect = viewModel::select,
                    onEdit = { dialogFor = it; dialogOpen = true },
                    onEditYaml = onEditYaml,
                    onExport = { profile ->
                        pendingExport = profile
                        exportLauncher.launch("${profile.name}.yaml")
                    },
                    onRefresh = viewModel::refresh,
                    onShareQr = { qrFor = it },
                    onDelete = viewModel::delete,
                    onAddRequested = { dialogFor = null; dialogOpen = true },
                )
            }
            if (state.busy) SubscriptionBusyOverlay()
        }
    }

    if (dialogOpen) {
        val editing = dialogFor
        SubscriptionDialog(
            initial = editing,
            onDismiss = { dialogOpen = false },
            onConfirm = { name, url, autoUpdate, intervalHours ->
                dialogOpen = false
                if (editing == null) {
                    viewModel.add(name, url, autoUpdate, intervalHours)
                } else {
                    viewModel.update(editing.id, name, url, autoUpdate, intervalHours)
                }
            },
            clipboardText = clipboard,
            onClipboardEmpty = { scope.launch { snackbarHost.showSnackbar(clipboardEmpty) } },
        )
    }
    if (installLink is InstallConfigLink.Valid) {
        // Same path as "Add from URL", so the busy overlay and failure
        // snackbar behave identically.
        InstallConfigDialog(
            link = installLink,
            onDismiss = onInstallLinkHandled,
            onConfirm = {
                onInstallLinkHandled()
                viewModel.add(installLink.name, installLink.url)
            },
        )
    }
    scannedLink?.let { link ->
        InstallConfigDialog(
            link = link,
            title = stringResource(R.string.subs_scan_confirm_title),
            onDismiss = { scanned = null },
            onConfirm = {
                scanned = null
                viewModel.add(link.name, link.url)
            },
        )
    }
    qrFor?.let { profile ->
        SubscriptionQrDialog(profile = profile, onDismiss = { qrFor = null })
    }
    SnackbarHost(snackbarHost)
}

@Composable
private fun ProxyGroupsRoute(bottomBar: @Composable () -> Unit) {
    val viewModel: ProxyGroupsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // On every visit too, not only app resume: see ProxyGroupsViewModel.onResume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    MeowScaffold(title = stringResource(R.string.proxy_groups_title), bottomBar = bottomBar) { padding ->
        ProxyGroupsScreen(
            state = state,
            contentPadding = padding,
            onToggleExpanded = viewModel::onToggleExpanded,
            onSelectNode = viewModel::onSelectNode,
            onTestGroup = viewModel::onTestGroup,
        )
    }
}

@Composable
private fun UtilityRoute(
    onTraffic: () -> Unit,
    onConnections: () -> Unit,
    onLogs: () -> Unit,
    onDns: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val vpnState by AppGraph.vpn.state.collectAsStateWithLifecycle()

    MeowScaffold(title = stringResource(R.string.utility_title), bottomBar = bottomBar) { padding ->
        UtilityScreen(
            engineOnline = vpnState == BaseService.State.Connected,
            contentPadding = padding,
            onConnections = onConnections,
            onDns = onDns,
            onTraffic = onTraffic,
            onLogs = onLogs,
        )
    }
}

@Composable
private fun TrafficRoute(onBack: () -> Unit) {
    val viewModel: io.github.madeye.meow.ui.screens.traffic.TrafficViewModel =
        viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(
        title = stringResource(R.string.traffic_title),
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        io.github.madeye.meow.ui.screens.traffic.TrafficScreen(
            state = state,
            contentPadding = padding,
            onSelectDay = viewModel::onSelectDay,
        )
    }
}

@Composable
private fun SettingsRoute(
    snackbarHost: SnackbarHostState,
    onPerAppProxy: () -> Unit,
    onRules: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val upToDateFmt = stringResource(R.string.settings_update_latest)
    val checkFailedFmt = stringResource(R.string.settings_update_failed)
    val noApp = stringResource(R.string.settings_update_no_app)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is SettingsEvent.UpToDate -> String.format(upToDateFmt, event.version)
                is SettingsEvent.UpdateCheckFailed -> String.format(checkFailedFmt, event.reason)
            }
            snackbarHost.showSnackbar(message)
        }
    }

    MeowScaffold(title = stringResource(R.string.settings_title), bottomBar = bottomBar) { padding ->
        SettingsScreen(
            state = state,
            contentPadding = padding,
            onPerAppProxy = onPerAppProxy,
            onRules = onRules,
            onShowExitIpChange = viewModel::onShowExitIpChange,
            onCheckForUpdates = {
                // Play policy lets a Play build update only through Play, so
                // it just opens the listing. The flag is a constant, so R8
                // drops the GitHub path from that build altogether.
                if (BuildConfig.PLAY_STORE) {
                    if (!context.openPlayListing()) scope.launch { snackbarHost.showSnackbar(noApp) }
                } else {
                    viewModel.checkForUpdates()
                }
            },
        )
    }
    // Never set in the Play build, but behind the flag too, so R8 drops the
    // dialog and its APK download link from that build as well.
    val release = state.update
    if (!BuildConfig.PLAY_STORE && release != null) {
        UpdateDialog(
            release = release,
            onDismiss = viewModel::onUpdateDialogClosed,
            onOpen = { url ->
                viewModel.onUpdateDialogClosed()
                if (!context.openUrl(url)) scope.launch { snackbarHost.showSnackbar(noApp) }
            },
        )
    }
    SnackbarHost(snackbarHost)
}

@Composable
private fun PerAppProxyRoute(onBack: () -> Unit) {
    val viewModel: PerAppProxyViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    MeowScaffold(
        title = stringResource(R.string.perapp_title),
        navigationIcon = { BackButton(onBack) },
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // Both act on the filtered list, so "select all" during a
                    // search means "all of these", not "all installed apps".
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.perapp_select_all)) },
                        onClick = {
                            menuOpen = false
                            viewModel.onSelectAllVisible(state.visibleApps)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.perapp_deselect_all)) },
                        onClick = {
                            menuOpen = false
                            viewModel.onDeselectAllVisible(state.visibleApps)
                        },
                    )
                }
            }
            IconButton(onClick = { viewModel.save(onBack) }) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.common_save))
            }
        },
    ) { padding ->
        PerAppProxyScreen(
            state = state,
            contentPadding = padding,
            onQueryChange = viewModel::onQueryChange,
            onToggleSystemApps = viewModel::onToggleSystemApps,
            onModeChange = viewModel::onModeChange,
            onToggleApp = viewModel::onToggleApp,
            iconLoader = viewModel::icon,
        )
    }
}

@Composable
private fun YamlEditorRoute(snackbarHost: SnackbarHostState, onBack: () -> Unit) {
    val viewModel: YamlEditorViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val initialText by viewModel.initialText.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val dirty by viewModel.dirty.collectAsStateWithLifecycle()
    val canRevert by viewModel.canRevert.collectAsStateWithLifecycle()
    val name by viewModel.profileName.collectAsStateWithLifecycle()
    val handle = rememberSoraEditorHandle()
    val scope = rememberCoroutineScope()
    val revertedMessage = stringResource(R.string.yaml_reverted)
    val savedMessage = stringResource(R.string.yaml_saved)
    var confirmRevert by remember { mutableStateOf(false) }

    MeowScaffold(
        title = name,
        navigationIcon = { BackButton(onBack) },
        actions = {
            YamlEditorActions(
                dirty = dirty,
                valid = error == null,
                canRevert = canRevert,
                onRevert = { confirmRevert = true },
                onSave = {
                    viewModel.save(handle.text()) {
                        scope.launch { snackbarHost.showSnackbar(savedMessage) }
                    }
                },
            )
        },
    ) { padding ->
        YamlEditorScreen(
            initialText = initialText,
            error = error,
            dirty = dirty,
            canRevert = canRevert,
            contentPadding = padding,
            onEdit = viewModel::onEdit,
            onRequestSave = { viewModel.save(it) {} },
            onRequestRevert = {
                viewModel.revert { reverted ->
                    handle.setText(reverted)
                    scope.launch { snackbarHost.showSnackbar(revertedMessage) }
                }
            },
            confirmRevert = confirmRevert,
            onDismissRevert = { confirmRevert = false },
            onNavigateBack = onBack,
            handle = handle,
        )
    }
    SnackbarHost(snackbarHost)
}

@Composable
private fun ConnectionsRoute(onBack: () -> Unit) {
    val viewModel: ConnectionsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(
        title = stringResource(R.string.connections_title),
        navigationIcon = { BackButton(onBack) },
        actions = {
            ConnectionsActions(
                tab = state.tab,
                hasConnections = state.connections.isNotEmpty(),
                hasRecent = state.recent.isNotEmpty(),
                onCloseAll = viewModel::closeAll,
                onClearRecent = viewModel::clearRecent,
            )
        },
    ) { padding ->
        ConnectionsScreen(
            state = state,
            contentPadding = padding,
            onQueryChange = viewModel::onQueryChange,
            onTabChange = viewModel::onTabChange,
            onClose = viewModel::close,
        )
    }
}

@Composable
private fun RulesRoute(onBack: () -> Unit) {
    val viewModel: RulesViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(
        title = if (state.rules.isEmpty()) {
            stringResource(R.string.rules_title)
        } else {
            pluralStringResource(R.plurals.rules_count, state.rules.size, state.rules.size)
        },
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        RulesScreen(
            state = state,
            contentPadding = padding,
            onQueryChange = viewModel::onQueryChange,
            onRetry = viewModel::load,
        )
    }
}

@Composable
private fun LogsRoute(onBack: () -> Unit) {
    val viewModel: LogsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var autoScroll by rememberSaveable { mutableStateOf(true) }

    MeowScaffold(
        title = stringResource(R.string.logs_title),
        navigationIcon = { BackButton(onBack) },
        actions = { LogsActions(autoScroll) { autoScroll = !autoScroll } },
    ) { padding ->
        LogsScreen(logs = logs, autoScroll = autoScroll, contentPadding = padding)
    }
}

@Composable
private fun DnsRoute(onBack: () -> Unit) {
    val viewModel: DnsViewModel = viewModel(factory = AppGraph.viewModelFactory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MeowScaffold(
        title = if (state.results.isEmpty()) {
            stringResource(R.string.dns_title)
        } else {
            stringResource(R.string.dns_title_count, state.results.size)
        },
        navigationIcon = { BackButton(onBack) },
    ) { padding ->
        DnsScreen(state = state, contentPadding = padding, onQueryChange = viewModel::onQueryChange)
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.common_back),
        )
    }
}
