package rida.pour.les.pros.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import rida.pour.les.pros.data.db.MIGRATION_1_2
import rida.pour.les.pros.data.db.RidaDatabase

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RidaDatabase::class.java)

    @Test fun migration_1_2_conserve_les_donnees() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO client (id, code, codeKey, isSystem, sortOrder, createdAt, updatedAt, deleted) VALUES ('c1', 'DERET', 'DERET', 0, 0, 0, 0, 0)")
            execSQL(
                "INSERT INTO rida_line (uuid, businessId, clientId, createdDate, interlocuteur, type, sujet, action, statut, echeance, realisation, commentaire, recul, hidden, hiddenReason, mergedInto, originalSujet, updatedAt) " +
                    "VALUES ('u1', 7, 'c1', 20000, 'Moi', 'ACTION', 'Sujet', 'Action', 'A_FAIRE', NULL, NULL, '', '', 0, NULL, NULL, NULL, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true, MIGRATION_1_2)
        db.query("SELECT businessId FROM rida_line").use {
            it.moveToFirst()
            assertEquals(7L, it.getLong(0))
        }
        db.execSQL("INSERT INTO chat_message (role, origin, text, detail, createdAt) VALUES ('USER', 'CHAT', 'bonjour', '', 0)")
    }

    private companion object {
        const val DB = "migration-test"
    }
}
