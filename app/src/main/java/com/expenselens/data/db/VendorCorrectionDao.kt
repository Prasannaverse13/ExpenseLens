package com.expenselens.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface VendorCorrectionDao {
    @Query(
        """
        SELECT * FROM vendor_corrections
        WHERE vendorKey = :key
        ORDER BY hitCount DESC, lastUsedAt DESC
        LIMIT 1
        """
    )
    suspend fun bestFor(key: String): VendorCorrectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(correction: VendorCorrectionEntity): Long

    @Query(
        """
        UPDATE vendor_corrections
        SET hitCount = hitCount + 1, lastUsedAt = :now
        WHERE id = :id
        """
    )
    suspend fun bump(id: Long, now: Long)
}
