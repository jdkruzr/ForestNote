package com.forestnote.core.format

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreservingDatabaseCallbackTest {
    @Test fun corruptionCallbackNeverInvokesDestructiveFrameworkRecovery() {
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)) { _, method, _ ->
            error("Callback must not inspect, close or delete original DB: ${method.name}")
        } as SupportSQLiteDatabase
        val failure = assertFailsWith<IllegalStateException> { PreservingDatabaseCallback().onCorruption(db) }
        assertTrue(failure.message!!.contains("original database preserved"))
    }
}
