package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpreadsheetDao {
    @Query("SELECT * FROM spreadsheets ORDER BY importedAt DESC")
    fun getAllSpreadsheets(): Flow<List<Spreadsheet>>

    @Query("SELECT * FROM spreadsheets WHERE id = :id LIMIT 1")
    suspend fun getSpreadsheetById(id: Long): Spreadsheet?

    @Query("SELECT * FROM spreadsheets WHERE name = :name ORDER BY versionNumber DESC")
    suspend fun getSpreadsheetsByName(name: String): List<Spreadsheet>

    @Query("SELECT * FROM spreadsheets WHERE parentGroupId = :groupId ORDER BY versionNumber DESC")
    fun getSpreadsheetsByGroup(groupId: String): Flow<List<Spreadsheet>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpreadsheet(spreadsheet: Spreadsheet): Long

    @Update
    suspend fun updateSpreadsheet(spreadsheet: Spreadsheet)

    @Query("DELETE FROM spreadsheets WHERE id = :id")
    suspend fun deleteSpreadsheetById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpreadsheetRows(rows: List<SpreadsheetRow>)

    @Query("SELECT * FROM spreadsheet_rows WHERE spreadsheetId = :spreadsheetId ORDER BY rowIndex ASC")
    fun getRowsForSpreadsheet(spreadsheetId: Long): Flow<List<SpreadsheetRow>>

    @Query("SELECT * FROM spreadsheet_rows WHERE spreadsheetId = :spreadsheetId AND searchText LIKE :query ORDER BY rowIndex ASC")
    fun searchRows(spreadsheetId: Long, query: String): Flow<List<SpreadsheetRow>>

    @Query("SELECT MAX(rowIndex) FROM spreadsheet_rows WHERE spreadsheetId = :spreadsheetId")
    suspend fun getMaxRowIndex(spreadsheetId: Long): Int?

    @Query("DELETE FROM spreadsheet_rows WHERE spreadsheetId = :spreadsheetId")
    suspend fun deleteRowsForSpreadsheet(spreadsheetId: Long)
}
