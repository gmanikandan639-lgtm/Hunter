package com.example.data

import kotlinx.coroutines.flow.Flow

class SpreadsheetRepository(private val spreadsheetDao: SpreadsheetDao) {

    val allSpreadsheets: Flow<List<Spreadsheet>> = spreadsheetDao.getAllSpreadsheets()

    suspend fun getSpreadsheetById(id: Long): Spreadsheet? {
        return spreadsheetDao.getSpreadsheetById(id)
    }

    suspend fun getSpreadsheetsByName(name: String): List<Spreadsheet> {
        return spreadsheetDao.getSpreadsheetsByName(name)
    }

    fun getSpreadsheetsByGroup(groupId: String): Flow<List<Spreadsheet>> {
        return spreadsheetDao.getSpreadsheetsByGroup(groupId)
    }

    suspend fun insertSpreadsheet(spreadsheet: Spreadsheet): Long {
        return spreadsheetDao.insertSpreadsheet(spreadsheet)
    }

    suspend fun updateSpreadsheet(spreadsheet: Spreadsheet) {
        spreadsheetDao.updateSpreadsheet(spreadsheet)
    }

    suspend fun deleteSpreadsheet(id: Long) {
        spreadsheetDao.deleteSpreadsheetById(id)
    }

    suspend fun insertRows(rows: List<SpreadsheetRow>) {
        spreadsheetDao.insertSpreadsheetRows(rows)
    }

    suspend fun getMaxRowIndex(spreadsheetId: Long): Int {
        return spreadsheetDao.getMaxRowIndex(spreadsheetId) ?: -1
    }

    fun getRowsForSpreadsheet(spreadsheetId: Long): Flow<List<SpreadsheetRow>> {
        return spreadsheetDao.getRowsForSpreadsheet(spreadsheetId)
    }

    fun searchRows(spreadsheetId: Long, query: String): Flow<List<SpreadsheetRow>> {
        val formattedQuery = "%$query%"
        return spreadsheetDao.searchRows(spreadsheetId, formattedQuery)
    }
}
