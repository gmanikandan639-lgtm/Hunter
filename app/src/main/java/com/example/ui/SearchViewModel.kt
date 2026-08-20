package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.ExcelParser
import com.example.data.Spreadsheet
import com.example.data.SpreadsheetRow
import com.example.data.SpreadsheetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray

enum class UserRole {
    ADMIN,
    VIEWER
}

data class UserProfile(
    val email: String,
    val name: String,
    val role: UserRole,
    val avatarColorHex: Long
)

enum class ImportMode {
    SAVE_AS_NEW,       // Preserves all old datasets and creates a new one (Default)
    SAVE_AS_VERSION,   // Creates a new version (v2, v3) linked to existing dataset while keeping old versions
    APPEND_TO_CURRENT  // Appends new rows to active dataset
}

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = SpreadsheetRepository(database.spreadsheetDao())

    // Mock User Accounts database
    val availableUsers = listOf(
        UserProfile("admin@sheetsearch.com", "Admin Manager", UserRole.ADMIN, 0xFFE53935),
        UserProfile("jane.staff@sheetsearch.com", "Jane (Staff Viewer)", UserRole.VIEWER, 0xFF1E88E5),
        UserProfile("robert.guest@sheetsearch.com", "Robert (Guest Viewer)", UserRole.VIEWER, 0xFF43A047)
    )
    
    // Auth & Multi-user state
    private val _currentUser = MutableStateFlow<UserProfile?>(availableUsers.first())
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    // UI state
    val allSpreadsheets: StateFlow<List<Spreadsheet>> = repository.allSpreadsheets.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )
    
    private val _selectedSpreadsheetId = MutableStateFlow<Long?>(null)
    val selectedSpreadsheetId: StateFlow<Long?> = _selectedSpreadsheetId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedColumnFilter = MutableStateFlow<Int?>(null) // null = All Columns
    val selectedColumnFilter: StateFlow<Int?> = _selectedColumnFilter.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _storageFilter = MutableStateFlow("ALL") // "ALL", "EXCEL", "CSV", "VERSIONS"
    val storageFilter: StateFlow<String> = _storageFilter.asStateFlow()

    // Observe active spreadsheet metadata
    val activeSpreadsheet: StateFlow<Spreadsheet?> = _selectedSpreadsheetId
        .flatMapLatest { id ->
            if (id == null) flowOf<Spreadsheet?>(null)
            else flow {
                emit(repository.getSpreadsheetById(id))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Version history list for the currently selected spreadsheet
    val currentSpreadsheetVersions: StateFlow<List<Spreadsheet>> = combine(
        allSpreadsheets,
        activeSpreadsheet
    ) { all, active ->
        if (active == null) emptyList()
        else {
            val groupId = active.parentGroupId
            if (groupId != null) {
                all.filter { it.parentGroupId == groupId || it.id == active.id }
                    .sortedByDescending { it.versionNumber }
            } else {
                all.filter { it.name.equals(active.name, ignoreCase = true) }
                    .sortedByDescending { it.importedAt }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Reactive list of filtered rows based on selected spreadsheet, column filter, and search query
    val filteredRows: StateFlow<List<SpreadsheetRow>> = combine(
        _selectedSpreadsheetId,
        _searchQuery,
        _selectedColumnFilter
    ) { sheetId, query, colFilter ->
        Triple(sheetId, query, colFilter)
    }.flatMapLatest { (sheetId, query, colFilter) ->
        if (sheetId == null) {
            flowOf(emptyList())
        } else {
            repository.getRowsForSpreadsheet(sheetId).map { rows ->
                if (query.isBlank()) {
                    rows
                } else if (colFilter != null) {
                    rows.filter { row ->
                        val cell = row.getValues().getOrNull(colFilter) ?: ""
                        cell.contains(query, ignoreCase = true)
                    }
                } else {
                    rows.filter { row ->
                        row.searchText.contains(query.trim(), ignoreCase = true)
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Total rows in active spreadsheet (unfiltered count)
    val totalRowsCount: StateFlow<Int> = _selectedSpreadsheetId
        .flatMapLatest { id ->
            if (id == null) flowOf(0)
            else repository.getRowsForSpreadsheet(id).map { it.size }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _sortColumnIndex = MutableStateFlow<Int?>(null)
    val sortColumnIndex: StateFlow<Int?> = _sortColumnIndex.asStateFlow()

    private val _sortAscending = MutableStateFlow(true)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    fun setColumnFilter(index: Int?) {
        _selectedColumnFilter.value = index
    }

    fun setStorageFilter(filter: String) {
        _storageFilter.value = filter
    }

    fun toggleSortColumn(index: Int) {
        if (_sortColumnIndex.value == index) {
            _sortAscending.value = !_sortAscending.value
        } else {
            _sortColumnIndex.value = index
            _sortAscending.value = true
        }
    }

    fun clearSort() {
        _sortColumnIndex.value = null
        _sortAscending.value = true
    }

    val sortedRows: StateFlow<List<SpreadsheetRow>> = combine(
        filteredRows,
        _sortColumnIndex,
        _sortAscending
    ) { rows, colIndex, ascending ->
        if (colIndex == null) {
            rows
        } else {
            rows.sortedWith { r1, r2 ->
                val v1List = r1.getValues()
                val v2List = r2.getValues()
                val v1 = v1List.getOrNull(colIndex) ?: ""
                val v2 = v2List.getOrNull(colIndex) ?: ""
                
                // Parse numbers safely, removing currency symbols, percentage sign, and commas
                val cleanV1 = v1.replace(Regex("[$,%]"), "").trim()
                val cleanV2 = v2.replace(Regex("[$,%]"), "").trim()

                val num1 = cleanV1.toDoubleOrNull()
                val num2 = cleanV2.toDoubleOrNull()
                
                val comp = if (num1 != null && num2 != null) {
                    num1.compareTo(num2)
                } else {
                    v1.lowercase().compareTo(v2.lowercase())
                }
                
                if (ascending) comp else -comp
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Preload default spreadsheet if database is empty
        viewModelScope.launch {
            allSpreadsheets.collect { list ->
                if (list.isEmpty()) {
                    importSpreadsheetFromCsvText(
                        name = "Tech Gadgets Inventory",
                        csvText = DEFAULT_SHEET_CSV,
                        fileFormat = "CSV",
                        importMode = ImportMode.SAVE_AS_NEW
                    )
                } else if (_selectedSpreadsheetId.value == null) {
                    _selectedSpreadsheetId.value = list.first().id
                }
            }
        }
    }

    fun login(email: String, password: String): Boolean {
        _loginError.value = null
        val normalizedEmail = email.trim().lowercase()
        val matchedUser = availableUsers.firstOrNull { it.email.lowercase() == normalizedEmail }
        
        if (matchedUser == null) {
            _loginError.value = "User not found. Use preconfigured user accounts below."
            return false
        }

        val expectedPassword = when (matchedUser.role) {
            UserRole.ADMIN -> "admin123"
            else -> "user123"
        }

        if (password != expectedPassword) {
            _loginError.value = "Incorrect password! Try 'admin123' for admin, or 'user123' for others."
            return false
        }

        _currentUser.value = matchedUser
        return true
    }

    fun quickLogin(user: UserProfile) {
        _loginError.value = null
        _currentUser.value = user
    }

    fun logout() {
        _currentUser.value = null
        _searchQuery.value = ""
    }

    fun selectSpreadsheet(id: Long) {
        _selectedSpreadsheetId.value = id
        _searchQuery.value = "" // Reset search when switching sheets
        clearSort()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun deleteSpreadsheetById(id: Long) {
        viewModelScope.launch {
            val wasSelected = (_selectedSpreadsheetId.value == id)
            repository.deleteSpreadsheet(id)
            if (wasSelected) {
                _selectedSpreadsheetId.value = null
                _searchQuery.value = ""
                allSpreadsheets.value.firstOrNull { it.id != id }?.let { nextSheet ->
                    _selectedSpreadsheetId.value = nextSheet.id
                }
            }
        }
    }

    fun deleteCurrentSpreadsheet() {
        val currentId = _selectedSpreadsheetId.value ?: return
        deleteSpreadsheetById(currentId)
    }

    fun importSpreadsheetFromCsvText(
        name: String,
        csvText: String,
        fileFormat: String = "CSV",
        importMode: ImportMode = ImportMode.SAVE_AS_NEW
    ): Boolean {
        if (csvText.isBlank() || name.isBlank()) {
            _importError.value = "Name or CSV content cannot be empty"
            return false
        }
        val parsedRows = ExcelParser.parseCsvText(csvText)
        return importSpreadsheetFromParsedList(name, parsedRows, fileFormat, importMode)
    }

    fun importSpreadsheetFromParsedList(
        name: String,
        parsedList: List<List<String>>,
        fileFormat: String = "CSV",
        importMode: ImportMode = ImportMode.SAVE_AS_NEW
    ): Boolean {
        if (parsedList.isEmpty() || name.isBlank()) {
            _importError.value = "File content or title cannot be empty"
            return false
        }

        _isImporting.value = true
        _importError.value = null

        try {
            val headers = parsedList.first()
            if (headers.isEmpty()) {
                _importError.value = "Failed to parse spreadsheet. No headers found."
                _isImporting.value = false
                return false
            }

            val dataRows = parsedList.drop(1).filter { row -> row.any { it.isNotBlank() } }

            viewModelScope.launch {
                when (importMode) {
                    ImportMode.SAVE_AS_NEW -> {
                        // Keep all old spreadsheets in database! Create new one alongside them.
                        val existingWithName = repository.getSpreadsheetsByName(name)
                        val version = if (existingWithName.isNotEmpty()) {
                            (existingWithName.maxOfOrNull { it.versionNumber } ?: 0) + 1
                        } else 1

                        val spreadsheet = Spreadsheet(
                            name = name.trim(),
                            columnNamesJson = JSONArray(headers).toString(),
                            fileFormat = fileFormat.uppercase(),
                            rowCount = dataRows.size,
                            versionNumber = version,
                            parentGroupId = "group_${name.trim().lowercase()}"
                        )
                        val newId = repository.insertSpreadsheet(spreadsheet)

                        val rowsToInsert = dataRows.mapIndexed { index, values ->
                            val searchTextBuilder = StringBuilder()
                            values.forEach { searchTextBuilder.append(it.lowercase()).append(" ") }
                            SpreadsheetRow(
                                spreadsheetId = newId,
                                rowIndex = index,
                                valuesJson = JSONArray(values).toString(),
                                searchText = searchTextBuilder.toString().trim()
                            )
                        }

                        repository.insertRows(rowsToInsert)
                        _selectedSpreadsheetId.value = newId
                    }

                    ImportMode.SAVE_AS_VERSION -> {
                        // Create a new version linked to the active spreadsheet while preserving the old version!
                        val currentActive = activeSpreadsheet.value
                        val parentGroup = currentActive?.parentGroupId ?: "group_${name.trim().lowercase()}"
                        val nextVersion = (currentActive?.versionNumber ?: 1) + 1

                        val spreadsheet = Spreadsheet(
                            name = (currentActive?.name ?: name).trim(),
                            columnNamesJson = JSONArray(headers).toString(),
                            fileFormat = fileFormat.uppercase(),
                            rowCount = dataRows.size,
                            versionNumber = nextVersion,
                            parentGroupId = parentGroup
                        )
                        val newId = repository.insertSpreadsheet(spreadsheet)

                        val rowsToInsert = dataRows.mapIndexed { index, values ->
                            val searchTextBuilder = StringBuilder()
                            values.forEach { searchTextBuilder.append(it.lowercase()).append(" ") }
                            SpreadsheetRow(
                                spreadsheetId = newId,
                                rowIndex = index,
                                valuesJson = JSONArray(values).toString(),
                                searchText = searchTextBuilder.toString().trim()
                            )
                        }

                        repository.insertRows(rowsToInsert)
                        _selectedSpreadsheetId.value = newId
                    }

                    ImportMode.APPEND_TO_CURRENT -> {
                        val currentId = _selectedSpreadsheetId.value
                        val currentActive = activeSpreadsheet.value
                        if (currentId != null && currentActive != null) {
                            val startIndex = repository.getMaxRowIndex(currentId) + 1
                            val rowsToInsert = dataRows.mapIndexed { index, values ->
                                val searchTextBuilder = StringBuilder()
                                values.forEach { searchTextBuilder.append(it.lowercase()).append(" ") }
                                SpreadsheetRow(
                                    spreadsheetId = currentId,
                                    rowIndex = startIndex + index,
                                    valuesJson = JSONArray(values).toString(),
                                    searchText = searchTextBuilder.toString().trim()
                                )
                            }
                            repository.insertRows(rowsToInsert)
                            repository.updateSpreadsheet(
                                currentActive.copy(
                                    rowCount = currentActive.rowCount + dataRows.size,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }

                _isImporting.value = false
            }
            return true
        } catch (e: Exception) {
            _importError.value = "Error saving spreadsheet: ${e.localizedMessage}"
            _isImporting.value = false
            return false
        }
    }

    companion object {
        const val DEFAULT_SHEET_CSV = """Product ID,Name,Category,Price,Stock,Supplier,Rating
GDG-001,Quantum Earbuds Pro,Audio,$129.99,85,AuraSound Labs,4.8
GDG-002,Pixel Watch Active,Wearables,$249.50,40,Google Device Div,4.6
GDG-003,Nebula Ultra Projector,Video,$599.00,12,NebulaStream Corp,4.9
GDG-004,Apex Mechanical Keyboard,Peripherals,$89.99,120,KeyForge Tech,4.7
GDG-005,Velo Smart Helmet,Fitness,$179.00,28,VeloMotion,4.4
GDG-006,VoltCore 20K Power Bank,Accessories,$39.99,250,VoltCore Ltd,4.5
GDG-007,SolStream 50W Solar Panel,Outdoor,$119.00,35,SolStream Energy,4.3
GDG-008,Titanium Frame Glasses,Apparel,$145.00,65,Titan Optics,4.6
GDG-009,AeroFly Folding Drone,Drones,$349.99,18,AeroFly Robotics,4.7
GDG-010,Chronos Wooden Wall Clock,Home Decor,$75.00,15,Chronos Design,4.2
GDG-011,Lumina RGB Desk Lamp,Home Office,$49.99,90,LuminaTech,4.5
GDG-012,SoundWave Waterproof Speaker,Audio,$69.99,110,AuraSound Labs,4.6
GDG-013,Apex Wireless Ergonomic Mouse,Peripherals,$59.99,140,KeyForge Tech,4.8
GDG-014,HyperCharge GaN Charger,Accessories,$29.99,180,VoltCore Ltd,4.7
GDG-015,FitTrack Smart Scales,Fitness,$34.99,75,VeloMotion,4.1"""
    }
}
