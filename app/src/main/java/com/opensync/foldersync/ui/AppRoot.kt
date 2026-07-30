package com.opensync.foldersync.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.opensync.foldersync.ui.common.verticalScrollbar
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opensync.foldersync.ui.accounts.AccountEditScreen
import com.opensync.foldersync.ui.accounts.AccountsScreen
import com.opensync.foldersync.pdf.PdfRequest
import com.opensync.foldersync.notes.NoteEditRequest
import com.opensync.foldersync.notes.NoteRequest
import com.opensync.foldersync.ui.explorer.ExplorerScreen
import com.opensync.foldersync.ui.gallery.GalleryScreen
import com.opensync.foldersync.ui.notes.NoteEditorScreen
import com.opensync.foldersync.ui.notes.NoteViewerScreen
import com.opensync.foldersync.ui.notes.NotesScreen
import com.opensync.foldersync.ui.pdf.PdfAnnotateScreen
import com.opensync.foldersync.ui.pdf.PdfFormScreen
import com.opensync.foldersync.ui.pdf.PdfPageOrganizerScreen
import com.opensync.foldersync.ui.pdf.PdfSignScreen
import com.opensync.foldersync.ui.pdf.PdfViewerScreen
import com.opensync.foldersync.ui.logs.SyncLogScreen
import com.opensync.foldersync.ui.pairs.FolderPairEditScreen
import com.opensync.foldersync.ui.pairs.FolderPairsScreen
import com.opensync.foldersync.ui.settings.SettingsScreen
import com.opensync.foldersync.ui.vault.VaultScreen
import com.opensync.foldersync.update.AppPrefs
import kotlinx.coroutines.launch

private data class DrawerEntry(val route: String, val label: String, val icon: ImageVector)

/** Top-level destinations whose selection is remembered across app launches. */
private val PERSISTED_ROUTES = setOf("files", "gallery", "notes", "vault", "pairs", "logs", "accounts", "settings")

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val context = LocalContext.current
    val prefs = remember { AppPrefs(context) }
    val startRoute = remember { prefs.lastRoute.takeIf { it in PERSISTED_ROUTES } ?: "files" }
    // Remember the last top-level screen so the app reopens where it was left.
    LaunchedEffect(currentRoute) {
        val r = currentRoute
        if (r != null && r in PERSISTED_ROUTES) prefs.lastRoute = r
    }

    val fullscreen by FullscreenState.active.collectAsState()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val navigateTo: (String) -> Unit = { route ->
        scope.launch { drawerState.close() }
        if (route != currentRoute) {
            navController.navigate(route) {
                launchSingleTop = true
                restoreState = true
                popUpTo(navController.graph.startDestinationId) { saveState = true }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || !fullscreen,
        drawerContent = {
            DrawerContent(currentRoute = currentRoute, onNavigate = navigateTo)
        }
    ) {
        NavHost(navController = navController, startDestination = startRoute) {
            composable("files") {
                ExplorerScreen(
                    openDrawer = openDrawer,
                    onOpenPdf = { path ->
                        PdfRequest.path = path
                        navController.navigate("pdf")
                    }
                )
            }
            composable("gallery") { GalleryScreen(openDrawer = openDrawer) }
            composable("vault") {
                VaultScreen(
                    openDrawer = openDrawer,
                    onOpenPdf = { p -> PdfRequest.path = p; navController.navigate("pdf") }
                )
            }
            composable("notes") {
                NotesScreen(
                    openDrawer = openDrawer,
                    onOpenPdf = { p -> PdfRequest.path = p; navController.navigate("pdf") },
                    onOpenNote = { p -> NoteRequest.path = p; navController.navigate("note_view") },
                    onNewNote = { dir ->
                        NoteEditRequest.path = null
                        NoteEditRequest.dir = dir
                        navController.navigate("note_edit")
                    },
                    onEditNote = { p ->
                        NoteEditRequest.path = p
                        NoteEditRequest.dir = null
                        navController.navigate("note_edit")
                    }
                )
            }
            composable("note_view") {
                NoteViewerScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { p ->
                        NoteEditRequest.path = p
                        NoteEditRequest.dir = null
                        navController.navigate("note_edit") { popUpTo("notes") }
                    }
                )
            }
            composable("note_edit") {
                NoteEditorScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() }
                )
            }
            composable("pdf") {
                PdfViewerScreen(
                    onBack = { navController.popBackStack() },
                    onEditPages = { navController.navigate("pdf_pages") },
                    onAnnotate = { navController.navigate("pdf_annotate") },
                    onSign = { navController.navigate("pdf_sign") },
                    onFillForm = { navController.navigate("pdf_form") }
                )
            }
            composable("pdf_pages") {
                PdfPageOrganizerScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { newPath ->
                        PdfRequest.path = newPath
                        navController.navigate("pdf") {
                            popUpTo("files") { inclusive = false }
                        }
                    }
                )
            }
            composable("pdf_annotate") {
                PdfAnnotateScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { newPath ->
                        PdfRequest.path = newPath
                        navController.navigate("pdf") {
                            popUpTo("files") { inclusive = false }
                        }
                    }
                )
            }
            composable("pdf_sign") {
                PdfSignScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { newPath ->
                        PdfRequest.path = newPath
                        navController.navigate("pdf") {
                            popUpTo("files") { inclusive = false }
                        }
                    }
                )
            }
            composable("pdf_form") {
                PdfFormScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { newPath ->
                        PdfRequest.path = newPath
                        navController.navigate("pdf") {
                            popUpTo("files") { inclusive = false }
                        }
                    }
                )
            }
            composable("pairs") {
                FolderPairsScreen(
                    openDrawer = openDrawer,
                    onAdd = { navController.navigate("pair/0") },
                    onEdit = { id -> navController.navigate("pair/$id") }
                )
            }
            composable("accounts") {
                AccountsScreen(
                    openDrawer = openDrawer,
                    onAdd = { navController.navigate("account/0") },
                    onEdit = { id -> navController.navigate("account/$id") }
                )
            }
            composable("logs") { SyncLogScreen(openDrawer = openDrawer) }
            composable("settings") { SettingsScreen(openDrawer = openDrawer) }

            composable(
                "pair/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                FolderPairEditScreen(
                    pairId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { navController.popBackStack() },
                    onAddAccount = { navController.navigate("account/0") }
                )
            }
            composable(
                "account/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                AccountEditScreen(
                    accountId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

@Composable
private fun DrawerContent(currentRoute: String?, onNavigate: (String) -> Unit) {
    ModalDrawerSheet {
        val scrollState = rememberScrollState()
        Column(Modifier.verticalScrollbar(scrollState).verticalScroll(scrollState)) {
            Text(
                "OpenSync",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(24.dp)
            )
            SectionLabel("Browse")
            DrawerRow("files", "Files", Icons.Filled.Folder, currentRoute, onNavigate)
            DrawerRow("gallery", "Gallery", Icons.Filled.PhotoLibrary, currentRoute, onNavigate)
            DrawerRow("notes", "Notes", Icons.Filled.Description, currentRoute, onNavigate)
            DrawerRow("vault", "Vault", Icons.Filled.Lock, currentRoute, onNavigate)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Backup")
            DrawerRow("pairs", "Folder pairs", Icons.Filled.Sync, currentRoute, onNavigate)
            DrawerRow("logs", "Sync log", Icons.Filled.History, currentRoute, onNavigate)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Manage")
            DrawerRow("accounts", "Accounts", Icons.Filled.Cloud, currentRoute, onNavigate)
            DrawerRow("settings", "Settings", Icons.Filled.Settings, currentRoute, onNavigate)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun DrawerRow(
    route: String,
    label: String,
    icon: ImageVector,
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = currentRoute == route,
        onClick = { onNavigate(route) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}
