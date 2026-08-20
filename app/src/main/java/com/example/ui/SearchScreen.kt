package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ExcelParser
import com.example.data.Spreadsheet
import com.example.data.SpreadsheetRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // ViewModel States
    val sheets by viewModel.allSpreadsheets.collectAsStateWithLifecycle()
    val activeSheet by viewModel.activeSpreadsheet.collectAsStateWithLifecycle()
    val currentVersions by viewModel.currentSpreadsheetVersions.collectAsStateWithLifecycle()
    val filteredRows by viewModel.sortedRows.collectAsStateWithLifecycle()
    val totalRowsCount by viewModel.totalRowsCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedColumnFilter by viewModel.selectedColumnFilter.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val sortColumnIndex by viewModel.sortColumnIndex.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()

    // Auth States
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()

    // Dialog & UI state
    var showImportDialog by remember { mutableStateOf(false) }
    var showStorageDialog by remember { mutableStateOf(false) }
    var showVersionHistoryDialog by remember { mutableStateOf(false) }
    var showDetailsDialog by remember { mutableStateOf<SpreadsheetRow?>(null) }
    var showProfileMenu by remember { mutableStateOf(false) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val columnNames = activeSheet?.getColumnNames() ?: emptyList()
                    val rows = filteredRows
                    val csvBuilder = StringBuilder()

                    // Header row
                    csvBuilder.append(columnNames.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\n")

                    // Data rows
                    rows.forEach { row ->
                        val rowVals = row.getValues()
                        csvBuilder.append(rowVals.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }).append("\n")
                    }

                    outputStream.write(csvBuilder.toString().toByteArray(Charsets.UTF_8))
                    Toast.makeText(context, "CSV exported successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // If no user is logged in, show the login screen
    if (currentUser == null) {
        LoginScreen(
            viewModel = viewModel,
            loginError = loginError,
            onLoginSuccess = {
                Toast.makeText(context, "Welcome back, ${it.name}!", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    val user = currentUser!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = "App Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Sheet Search",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (activeSheet != null) {
                                    FormatBadge(format = activeSheet?.fileFormat ?: "CSV")
                                    VersionBadge(version = activeSheet?.versionNumber ?: 1)
                                }
                            }
                            Text(
                                text = activeSheet?.name ?: "Multi-format CSV, XLSX & XLS Database",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    // Storage / Database Library button
                    IconButton(
                        onClick = { showStorageDialog = true },
                        modifier = Modifier.testTag("open_storage_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (sheets.isNotEmpty()) {
                                    Badge { Text("${sheets.size}") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = "Storage History & Datasets",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Prominent Top Bar Upload Button (CSV, Excel, XLS)
                    FilledTonalButton(
                        onClick = { showImportDialog = true },
                        modifier = Modifier
                            .testTag("top_upload_button")
                            .padding(horizontal = 4.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "Upload File",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Upload",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Profile/Role switcher dropdown button
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        IconButton(
                            onClick = { showProfileMenu = !showProfileMenu },
                            modifier = Modifier.testTag("profile_avatar_button")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(user.avatarColorHex)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = user.name.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showProfileMenu,
                            onDismissRequest = { showProfileMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(user.name, fontWeight = FontWeight.Bold)
                                        Text(user.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                        Text("Role: ${user.role.name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {},
                                enabled = false
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Database Storage (${sheets.size} Datasets)")
                                    }
                                },
                                onClick = {
                                    showProfileMenu = false
                                    showStorageDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Upload CSV / Excel / XLS")
                                    }
                                },
                                onClick = {
                                    showProfileMenu = false
                                    showImportDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Default.SwitchAccount, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Switch User")
                                    }
                                },
                                onClick = {
                                    showProfileMenu = false
                                    viewModel.logout()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Log Out")
                                    }
                                },
                                onClick = {
                                    showProfileMenu = false
                                    viewModel.logout()
                                }
                            )
                        }
                    }

                    // Delete current sheet option
                    if (activeSheet != null) {
                        IconButton(
                            onClick = { viewModel.deleteCurrentSpreadsheet() },
                            modifier = Modifier.testTag("delete_sheet_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete Active Sheet",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showImportDialog = true },
                icon = { Icon(Icons.Default.CloudUpload, contentDescription = "Upload icon") },
                text = { Text("Upload File") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("import_fab")
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Horizontal Storage & Sheet Selector Row
            if (sheets.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Stored Datasets (${sheets.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "• Keeps all old & new",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (currentVersions.size > 1) {
                            TextButton(
                                onClick = { showVersionHistoryDialog = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Revisions (${currentVersions.size})", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        TextButton(
                            onClick = { showStorageDialog = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.ManageHistory, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("All Storage", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Chips for quick switching between stored spreadsheets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sheets.forEach { sheet ->
                        val isSelected = sheet.id == activeSheet?.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.selectSpreadsheet(sheet.id) },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = sheet.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "[${sheet.fileFormat}]",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary
                                    )
                                    if (sheet.versionNumber > 1) {
                                        Text(
                                            text = "v${sheet.versionNumber}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.TableChart,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("sheet_chip_${sheet.id}")
                        )
                    }

                    // Add new sheet quick chip
                    SuggestionChip(
                        onClick = { showImportDialog = true },
                        label = { Text("+ Upload New") },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            // Search and Controls
            if (activeSheet != null) {
                val columnNames = activeSheet?.getColumnNames() ?: emptyList()

                // Dedicated Real-Time Search Bar Component positioned above the table view
                SpreadsheetSearchBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                    columnNames = columnNames,
                    selectedColumnFilter = selectedColumnFilter,
                    onColumnFilterChange = { viewModel.setColumnFilter(it) },
                    totalRowsCount = totalRowsCount,
                    matchingRowsCount = filteredRows.size,
                    onExportCsv = if (filteredRows.isNotEmpty()) {
                        {
                            val suggestedName = "${activeSheet?.name ?: "spreadsheet"}_export.csv"
                            exportCsvLauncher.launch(suggestedName)
                        }
                    } else null
                )

                Spacer(modifier = Modifier.height(4.dp))

                // The Spreadsheet Grid View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    if (filteredRows.isEmpty()) {
                        EmptySearchResultsState(
                            query = searchQuery,
                            onClearSearch = {
                                viewModel.updateSearchQuery("")
                                viewModel.setColumnFilter(null)
                            },
                            onUploadNew = { showImportDialog = true }
                        )
                    } else {
                        SpreadsheetTableGrid(
                            columns = columnNames,
                            rows = filteredRows,
                            sortColumnIndex = sortColumnIndex,
                            sortAscending = sortAscending,
                            searchQuery = searchQuery,
                            onHeaderClick = { viewModel.toggleSortColumn(it) },
                            onRowClick = { showDetailsDialog = it }
                        )
                    }
                }
            } else {
                // If there are no spreadsheets uploaded yet
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(28.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = "Upload File",
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Upload Your Spreadsheet",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Accepts CSV, Excel (.xlsx), and legacy Excel (.xls) formats. Stored persistently in local database alongside all previous versions.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { showImportDialog = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Upload CSV / Excel / XLS", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    // Row Detail Dialog
    showDetailsDialog?.let { row ->
        val columnNames = activeSheet?.getColumnNames() ?: emptyList()
        val cellValues = row.getValues()
        RowDetailDialog(
            columnNames = columnNames,
            cellValues = cellValues,
            rowIndex = row.rowIndex,
            searchQuery = searchQuery,
            onDismiss = { showDetailsDialog = null },
            onCopyField = { value ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Copied Cell", value)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Database Storage Manager Dialog (Shows all old & new spreadsheets)
    if (showStorageDialog) {
        DatabaseStorageDialog(
            sheets = sheets,
            activeSheetId = activeSheet?.id,
            onSelectSheet = { id ->
                viewModel.selectSpreadsheet(id)
                showStorageDialog = false
            },
            onDeleteSheet = { id ->
                viewModel.deleteSpreadsheetById(id)
            },
            onUploadNew = {
                showStorageDialog = false
                showImportDialog = true
            },
            onDismiss = { showStorageDialog = false }
        )
    }

    // Version History Dialog for current sheet
    if (showVersionHistoryDialog && activeSheet != null) {
        VersionHistoryDialog(
            currentSheet = activeSheet!!,
            versions = currentVersions,
            onSelectVersion = { id ->
                viewModel.selectSpreadsheet(id)
                showVersionHistoryDialog = false
            },
            onDismiss = { showVersionHistoryDialog = false }
        )
    }

    // Comprehensive Multi-Format File Importer Dialog (CSV, XLSX, XLS)
    if (showImportDialog) {
        MultiFormatImportDialog(
            isImporting = isImporting,
            importError = importError,
            activeSheet = activeSheet,
            onDismiss = {
                showImportDialog = false
                viewModel.clearImportError()
            },
            onImport = { name, parsedList, format, mode ->
                val success = viewModel.importSpreadsheetFromParsedList(name, parsedList, format, mode)
                if (success) {
                    showImportDialog = false
                    Toast.makeText(context, "Saved to database! Old and new datasets preserved.", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

// =====================================================================
// Format & Version Badges
// =====================================================================

@Composable
fun FormatBadge(format: String) {
    val (bgColor, textColor) = when (format.uppercase()) {
        "XLSX" -> Color(0xFF1B5E20) to Color(0xFFE8F5E9)
        "XLS" -> Color(0xFF00695C) to Color(0xFFE0F2F1)
        "CSV" -> Color(0xFF0D47A1) to Color(0xFFE3F2FD)
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = format.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun VersionBadge(version: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = "v$version",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

// =====================================================================
// Comprehensive Multi-Format Import Dialog (CSV, XLSX, XLS)
// =====================================================================

@Composable
fun MultiFormatImportDialog(
    isImporting: Boolean,
    importError: String?,
    activeSheet: Spreadsheet?,
    onDismiss: () -> Unit,
    onImport: (name: String, parsedList: List<List<String>>, format: String, mode: ImportMode) -> Unit
) {
    val context = LocalContext.current
    var sheetName by remember { mutableStateOf("") }
    var detectedFormat by remember { mutableStateOf("CSV") }
    var parsedRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var rawTextContent by remember { mutableStateOf("") }
    var selectedImportMode by remember { mutableStateOf(ImportMode.SAVE_AS_NEW) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = File Upload, 1 = Paste Text / Templates

    // Launcher for file picking supporting all spreadsheet formats (*.csv, *.xlsx, *.xls, *.tsv)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                val filename = uri.lastPathSegment ?: ""
                val lowerFilename = filename.lowercase()
                
                // Determine initial format
                detectedFormat = when {
                    lowerFilename.endsWith(".xlsx") -> "XLSX"
                    lowerFilename.endsWith(".xls") -> "XLS"
                    lowerFilename.endsWith(".csv") -> "CSV"
                    lowerFilename.endsWith(".tsv") -> "TSV"
                    else -> "CSV"
                }

                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val rows = ExcelParser.parseAny(inputStream, filename)
                    if (rows.isNotEmpty()) {
                        parsedRows = rows
                        rawTextContent = rows.take(10).joinToString("\n") { row -> row.joinToString(",") }
                        
                        // Suggest sheet name
                        val cleanName = filename.substringAfterLast("/").substringBeforeLast(".")
                        if (sheetName.isBlank()) {
                            sheetName = cleanName.ifBlank { "Spreadsheet_${System.currentTimeMillis() % 1000}" }
                        }
                        Toast.makeText(context, "Loaded ${rows.size} rows (${rows.firstOrNull()?.size ?: 0} columns) from $filename", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "File is empty or format could not be parsed.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Upload Spreadsheet to Database",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Supports CSV, Excel (.xlsx), and Legacy Excel (.xls). All uploads are stored in the database, keeping previous and new versions accessible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                // Input Tab switcher
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Pick File (.csv/.xlsx/.xls)") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Paste / Templates") }
                    )
                }

                // Title Input
                OutlinedTextField(
                    value = sheetName,
                    onValueChange = { sheetName = it },
                    label = { Text("Spreadsheet Title") },
                    placeholder = { Text("e.g. Q3 Sales Report") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_name_field"),
                    shape = RoundedCornerShape(8.dp)
                )

                if (selectedTab == 0) {
                    // File Pick button
                    Button(
                        onClick = { filePickerLauncher.launch("*/*") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("import_file_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Text(
                                text = if (parsedRows.isNotEmpty()) "Change File (.csv, .xlsx, .xls)" else "Select CSV / Excel / XLS File",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Parsed preview card if file selected
                    if (parsedRows.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Text("File Parsed Successfully", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                    }
                                    FormatBadge(format = detectedFormat)
                                }
                                Text(
                                    text = "Rows: ${parsedRows.size - 1} data rows | Columns: ${parsedRows.firstOrNull()?.size ?: 0} (${parsedRows.firstOrNull()?.joinToString(", ") ?: ""})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    // Paste or Sample Templates
                    Text(
                        text = "Or pick a sample dataset:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AssistChip(
                            onClick = {
                                sheetName = "Customer Feedback & NPS"
                                rawTextContent = "Customer ID,Customer Name,Channel,Rating,Feedback Category,Status\n" +
                                        "CUST-101,Sarah Connor,Email,9,Product Quality,Resolved\n" +
                                        "CUST-102,John Matrix,Phone,10,Customer Support,Resolved\n" +
                                        "CUST-103,Ellen Ripley,Web,6,Shipping Delays,Pending\n" +
                                        "CUST-104,Marty McFly,Chat,8,UI Navigation,Resolved\n" +
                                        "CUST-105,Bruce Wayne,Email,10,Enterprise Security,Resolved\n" +
                                        "CUST-106,Diana Prince,Web,9,Feature Request,In Review\n" +
                                        "CUST-107,Tony Stark,Chat,7,API Integration,In Progress"
                                parsedRows = ExcelParser.parseCsvText(rawTextContent)
                                detectedFormat = "CSV"
                            },
                            label = { Text("Customer NPS") },
                            leadingIcon = { Icon(Icons.Default.Reviews, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )

                        AssistChip(
                            onClick = {
                                sheetName = "Project Roadmap & Tasks"
                                rawTextContent = "Task ID,Title,Assignee,Priority,Sprint,Progress\n" +
                                        "TSK-201,Implement Offline Cache,Alex Chen,High,Sprint 24,Completed\n" +
                                        "TSK-202,Real-time Highlighting,Elena Rostova,Urgent,Sprint 24,In Review\n" +
                                        "TSK-203,Excel Parser Optimization,Marcus Vance,Medium,Sprint 25,In Progress\n" +
                                        "TSK-204,CSV Export Formatting,Sarah Lin,Low,Sprint 25,Backlog\n" +
                                        "TSK-205,UI Accessibility Audit,David Kim,High,Sprint 24,Completed"
                                parsedRows = ExcelParser.parseCsvText(rawTextContent)
                                detectedFormat = "CSV"
                            },
                            label = { Text("Project Tasks") },
                            leadingIcon = { Icon(Icons.Default.Task, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )

                        AssistChip(
                            onClick = {
                                sheetName = "Global Sales Figures"
                                rawTextContent = "Invoice ID,Region,Product,Units Sold,Revenue,Status\n" +
                                        "INV-901,North America,Enterprise Cloud,45,$67500.00,Paid\n" +
                                        "INV-902,Europe,Security Suite,120,$24000.00,Paid\n" +
                                        "INV-903,Asia-Pacific,Database Cluster,18,$54000.00,Pending\n" +
                                        "INV-904,Latin America,Developer License,80,$12000.00,Paid\n" +
                                        "INV-905,Middle East,Storage Node,30,$36000.00,Paid"
                                parsedRows = ExcelParser.parseCsvText(rawTextContent)
                                detectedFormat = "CSV"
                            },
                            label = { Text("Sales Report") },
                            leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }

                    OutlinedTextField(
                        value = rawTextContent,
                        onValueChange = {
                            rawTextContent = it
                            parsedRows = ExcelParser.parseCsvText(it)
                            detectedFormat = "PASTED"
                        },
                        placeholder = {
                            Text(
                                "Paste CSV or tab-separated rows here...\n" +
                                        "Column1,Column2,Column3\n" +
                                        "Value1,Value2,Value3"
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .testTag("import_paste_field"),
                        shape = RoundedCornerShape(8.dp),
                        maxLines = 8
                    )
                }

                // Storage Retention Mode Selection (Keep Old & New)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                
                Text(
                    text = "Storage & Versioning Mode:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedImportMode = ImportMode.SAVE_AS_NEW }
                    ) {
                        RadioButton(
                            selected = selectedImportMode == ImportMode.SAVE_AS_NEW,
                            onClick = { selectedImportMode = ImportMode.SAVE_AS_NEW }
                        )
                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text("Save as New Dataset", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("Keeps all previous & new files in storage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    if (activeSheet != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedImportMode = ImportMode.SAVE_AS_VERSION }
                        ) {
                            RadioButton(
                                selected = selectedImportMode == ImportMode.SAVE_AS_VERSION,
                                onClick = { selectedImportMode = ImportMode.SAVE_AS_VERSION }
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text("Save as New Version (v${(activeSheet.versionNumber) + 1})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text("Links to '${activeSheet.name}' and preserves v${activeSheet.versionNumber} in history", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedImportMode = ImportMode.APPEND_TO_CURRENT }
                        ) {
                            RadioButton(
                                selected = selectedImportMode == ImportMode.APPEND_TO_CURRENT,
                                onClick = { selectedImportMode = ImportMode.APPEND_TO_CURRENT }
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text("Append Rows to Active Sheet", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                Text("Adds parsed rows to the bottom of '${activeSheet.name}'", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }

                if (importError != null) {
                    Text(
                        text = importError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isImporting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalRows = if (parsedRows.isNotEmpty()) parsedRows else ExcelParser.parseCsvText(rawTextContent)
                    onImport(sheetName, finalRows, detectedFormat, selectedImportMode)
                },
                enabled = !isImporting && sheetName.isNotBlank() && (parsedRows.isNotEmpty() || rawTextContent.isNotBlank()),
                modifier = Modifier.testTag("import_confirm_button")
            ) {
                Text("Save & Index in Database")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// =====================================================================
// Database Storage & Version History Dialog
// =====================================================================

@Composable
fun DatabaseStorageDialog(
    sheets: List<Spreadsheet>,
    activeSheetId: Long?,
    onSelectSheet: (Long) -> Unit,
    onDeleteSheet: (Long) -> Unit,
    onUploadNew: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchStorageQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "EXCEL", "CSV"

    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()) }

    val filteredSheets = remember(sheets, searchStorageQuery, selectedFilter) {
        sheets.filter { sheet ->
            val matchesQuery = searchStorageQuery.isBlank() || sheet.name.contains(searchStorageQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "EXCEL" -> sheet.fileFormat.equals("XLSX", ignoreCase = true) || sheet.fileFormat.equals("XLS", ignoreCase = true)
                "CSV" -> sheet.fileFormat.equals("CSV", ignoreCase = true) || sheet.fileFormat.equals("PASTED", ignoreCase = true)
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Database Storage", fontWeight = FontWeight.Bold)
                }
                Badge { Text("${sheets.size} Datasets") }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Persistent local database keeping all newly uploaded and past datasets intact with full search indexing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                // Search & Filter
                OutlinedTextField(
                    value = searchStorageQuery,
                    onValueChange = { searchStorageQuery = it },
                    placeholder = { Text("Filter saved datasets...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                // Filter tabs
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("All (${sheets.size})") }
                    )
                    FilterChip(
                        selected = selectedFilter == "EXCEL",
                        onClick = { selectedFilter = "EXCEL" },
                        label = { Text("Excel (.xlsx/.xls)") }
                    )
                    FilterChip(
                        selected = selectedFilter == "CSV",
                        onClick = { selectedFilter = "CSV" },
                        label = { Text("CSV / Text") }
                    )
                }

                if (filteredSheets.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No matching spreadsheets found in storage.", color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredSheets, key = { it.id }) { sheet ->
                            val isActive = sheet.id == activeSheetId
                            Card(
                                onClick = { onSelectSheet(sheet.id) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = sheet.name,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            FormatBadge(format = sheet.fileFormat)
                                            VersionBadge(version = sheet.versionNumber)
                                        }

                                        Text(
                                            text = "Rows: ${sheet.rowCount} | Cols: ${sheet.getColumnNames().size} • ${dateFormatter.format(Date(sheet.importedAt))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }

                                    if (sheets.size > 1) {
                                        IconButton(
                                            onClick = { onDeleteSheet(sheet.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onUploadNew) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Upload New File")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// =====================================================================
// Version History Dialog (Allows switching between revisions of a sheet)
// =====================================================================

@Composable
fun VersionHistoryDialog(
    currentSheet: Spreadsheet,
    versions: List<Spreadsheet>,
    onSelectVersion: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm:ss a", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Revision History: ${currentSheet.name}", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "All updates and revisions are preserved in database storage. Tap any version to load it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(versions) { ver ->
                        val isCurrent = ver.id == currentSheet.id
                        Card(
                            onClick = { onSelectVersion(ver.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                VersionBadge(version = ver.versionNumber)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isCurrent) "Current Active (v${ver.versionNumber})" else "Version ${ver.versionNumber}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${ver.rowCount} rows • Format: ${ver.fileFormat} • ${dateFormatter.format(Date(ver.importedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                                if (isCurrent) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// =====================================================================
// Login & Auth Screen
// =====================================================================

@Composable
fun LoginScreen(
    viewModel: SearchViewModel,
    loginError: String?,
    onLoginSuccess: (UserProfile) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        // Large Premium Icon Banner
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Analytics,
                contentDescription = "Search Sheet Logo",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Sheet Search Engine",
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Offline indexing & persistent database for CSV, XLSX, and XLS files",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Main Login Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Sign In",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email Address") },
                    placeholder = { Text("admin@sheetsearch.com") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("email_field"),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    placeholder = { Text("admin123 / user123") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle password visibility"
                            )
                        }
                    },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("password_field"),
                    shape = RoundedCornerShape(12.dp)
                )

                if (loginError != null) {
                    Text(
                        text = loginError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                Button(
                    onClick = {
                        val success = viewModel.login(email, password)
                        if (success) {
                            val loggedInUser = viewModel.currentUser.value
                            if (loggedInUser != null) onLoginSuccess(loggedInUser)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("login_submit_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Sign In Securely", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Quick login section
        Text(
            text = "OR CHOOSE A DEMO USER INSTANTLY",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        // Iterate through preconfigured users
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            viewModel.availableUsers.forEach { user ->
                val isAdminRole = user.role == UserRole.ADMIN
                OutlinedCard(
                    onClick = {
                        viewModel.quickLogin(user)
                        onLoginSuccess(user)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_user_${user.email}"),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(user.avatarColorHex)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = user.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.name,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Role: ${user.role.name} | PW: " + (if (isAdminRole) "admin123" else "user123"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Icon(
                            imageVector = if (isAdminRole) Icons.Default.AdminPanelSettings else Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = if (isAdminRole) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// =====================================================================
// Table Grid & Cell Highlighting
// =====================================================================

@Composable
fun HighlightedText(
    text: String,
    query: String,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
        Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }

    val annotatedString = remember(text, query) {
        buildAnnotatedString {
            var startIndex = 0
            val lowerText = text.lowercase()
            val lowerQuery = query.lowercase()
            while (true) {
                val index = lowerText.indexOf(lowerQuery, startIndex)
                if (index == -1) {
                    append(text.substring(startIndex))
                    break
                }
                append(text.substring(startIndex, index))
                withStyle(SpanStyle(
                    fontWeight = FontWeight.Bold,
                    background = highlightColor.copy(alpha = 0.25f),
                    color = highlightColor
                )) {
                    append(text.substring(index, index + query.length))
                }
                startIndex = index + query.length
            }
        }
    }

    Text(
        text = annotatedString,
        style = style,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow
    )
}

@Composable
fun SpreadsheetTableGrid(
    columns: List<String>,
    rows: List<SpreadsheetRow>,
    sortColumnIndex: Int? = null,
    sortAscending: Boolean = true,
    searchQuery: String = "",
    onHeaderClick: (Int) -> Unit = {},
    onRowClick: (SpreadsheetRow) -> Unit
) {
    val horizontalScrollState = rememberScrollState()
    val cellWidth = 140.dp
    val headerHeight = 44.dp
    val rowHeight = 48.dp

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky Header with horizontal scroll sync
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .horizontalScroll(horizontalScrollState)
        ) {
            // Index column header
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(headerHeight)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // Cell Headers
            columns.forEachIndexed { colIndex, colName ->
                val isSorted = sortColumnIndex == colIndex
                Row(
                    modifier = Modifier
                        .width(cellWidth)
                        .height(headerHeight)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        .clickable { onHeaderClick(colIndex) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = colName,
                        fontWeight = FontWeight.Bold,
                        color = if (isSorted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSorted) {
                        Icon(
                            imageVector = if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = "Sort indicator",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Table Content Rows
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                val values = row.getValues()
                val isEven = index % 2 == 0
                val rowBackground = if (isEven) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBackground)
                        .clickable { onRowClick(row) }
                        .horizontalScroll(horizontalScrollState)
                        .testTag("table_row_$index")
                ) {
                    // Row index number cell
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(rowHeight)
                            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Row data cells
                    columns.forEachIndexed { colIndex, _ ->
                        val cellValue = values.getOrNull(colIndex) ?: ""
                        Box(
                            modifier = Modifier
                                .width(cellWidth)
                                .height(rowHeight)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            HighlightedText(
                                text = cellValue,
                                query = searchQuery,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySearchResultsState(
    query: String,
    onClearSearch: () -> Unit,
    onUploadNew: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (query.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (query.isNotBlank()) "No records matching \"$query\"" else "Spreadsheet is empty",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (query.isNotBlank()) {
                "Try searching by another column, partial keyword, or clear your query."
            } else {
                "Upload a CSV or Excel spreadsheet to begin."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (query.isNotBlank()) {
            OutlinedButton(
                onClick = onClearSearch,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear Search Query")
            }
        } else {
            Button(
                onClick = onUploadNew,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Upload Spreadsheet")
            }
        }
    }
}

@Composable
fun RowDetailDialog(
    columnNames: List<String>,
    cellValues: List<String>,
    rowIndex: Int,
    searchQuery: String,
    onDismiss: () -> Unit,
    onCopyField: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FormatListNumbered,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Row #${rowIndex + 1} Record Details",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                columnNames.forEachIndexed { index, colName ->
                    val value = cellValues.getOrNull(index) ?: ""
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = colName.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                HighlightedText(
                                    text = value.ifBlank { "—" },
                                    query = searchQuery,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { onCopyField(value) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy cell value",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}
