package com.abuzahra.manager.api

import android.util.Log
import com.abuzahra.manager.Config
import com.abuzahra.manager.model.Command
import com.google.firebase.database.*
import com.google.gson.Gson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object FirebaseManager {

    private val gson = Gson()
    private const val TAG = "FirebaseManager"

    // Check if Firebase database is available
    private val firebaseAvailable = AtomicBoolean(true)
    private var lastConnectionError: String? = null

    @Volatile
    private var databaseInstance: FirebaseDatabase? = null
    
    private fun getDatabase(): FirebaseDatabase? {
        if (databaseInstance != null) return databaseInstance
        return try {
            val db = FirebaseDatabase.getInstance()
            // Configure for better reliability - only set once
            try {
                db.setPersistenceEnabled(false) // Disable local cache for real-time commands
            } catch (e: Exception) {
                Log.w(TAG, "setPersistenceEnabled error (may already be configured)", e)
            }
            databaseInstance = db
            db
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get FirebaseDatabase instance", e)
            firebaseAvailable.set(false)
            lastConnectionError = e.message
            null
        }
    }

    private fun getRef(path: String): DatabaseReference? {
        if (!firebaseAvailable.get()) {
            // Try to re-test connection (non-suspend version)
            try { testConnectionSync() } catch (_: Exception) {}
            if (!firebaseAvailable.get()) return null
        }
        val db = getDatabase() ?: return null
        return try {
            db.getReferenceFromUrl(Config.FIREBASE_RTDB_URL).child(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get reference for path: $path", e)
            firebaseAvailable.set(false)
            lastConnectionError = e.message
            null
        }
    }

    fun isAvailable(): Boolean = firebaseAvailable.get()
    fun getLastError(): String? = lastConnectionError

    // ===== LISTEN FOR COMMANDS =====
    fun listenForCommands(deviceId: String): Flow<Command> = callbackFlow {
        val ref = getRef("commands/$deviceId")
        if (ref == null) {
            Log.e(TAG, "Cannot listen for commands - Firebase not available")
            close(Exception("Firebase not available: $lastConnectionError"))
            return@callbackFlow
        }

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val data = snapshot.value as? Map<*, *> ?: run {
                        Log.w(TAG, "onChildAdded: unexpected data type: ${snapshot.value?.javaClass?.simpleName}")
                        return
                    }
                    val json = gson.toJson(data)
                    Log.d(TAG, "onChildAdded: raw=$json")
                    val cmd = gson.fromJson(json, Command::class.java)
                    cmd.let { trySend(it) }
                    // Remove command after reading
                    snapshot.ref.removeValue().addOnFailureListener { err ->
                        Log.w(TAG, "Failed to remove command ${cmd.id}: ${err.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onChildAdded error", e)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Commands listener cancelled: ${error.toException()}")
                firebaseAvailable.set(false)
                lastConnectionError = error.message
            }
        }
        ref.addChildEventListener(listener)
        Log.i(TAG, "Firebase command listener active for device: $deviceId")

        awaitClose {
            try {
                ref.removeEventListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing listener", e)
            }
        }
    }

    // ===== SUBMIT RESULT =====
    fun submitResult(deviceId: String, cmdId: String, command: String, status: String, result: Any?) {
        try {
            val ref = getRef("results/$deviceId/$cmdId") ?: run {
                Log.e(TAG, "submitResult: Firebase not available")
                return
            }

            val resultData = mapOf(
                "result" to (result?.let { if (it is String) it else gson.toJson(it) } ?: "OK"),
                "command" to command,
                "status" to status,
                "timestamp" to System.currentTimeMillis()
            )

            ref.setValue(resultData)
                .addOnSuccessListener {
                    Log.d(TAG, "Firebase result submitted: $cmdId")
                    // Auto-delete after 30 seconds
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { ref.removeValue() } catch (_: Exception) {}
                    }, 30000)
                }
                .addOnFailureListener { err ->
                    Log.e(TAG, "Firebase submitResult failed for $cmdId: ${err.message}")
                    firebaseAvailable.set(false)
                    lastConnectionError = err.message
                }
        } catch (e: Exception) {
            Log.e(TAG, "submitResult error", e)
            firebaseAvailable.set(false)
            lastConnectionError = e.message
        }
    }

    // ===== CHECK LINK CODE =====
    suspend fun checkLinkCode(code: String): Pair<Boolean, String> = suspendCancellableCoroutine { cont ->
        val ref = getRef("link_codes/$code")
        if (ref == null) {
            cont.resume(Pair(false, "Firebase not available"))
            return@suspendCancellableCoroutine
        }

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val data = snapshot.value as? Map<*, *>
                if (data == null) {
                    cont.resume(Pair(false, "Invalid code"))
                    return
                }
                val used = data["used"] as? Boolean ?: false
                if (used) {
                    cont.resume(Pair(false, "Code already used"))
                } else {
                    cont.resume(Pair(true, "Code valid"))
                }
            }
            override fun onCancelled(error: DatabaseError) {
                firebaseAvailable.set(false)
                lastConnectionError = error.message
                cont.resume(Pair(false, error.message))
            }
        })
    }

    // ===== TEST CONNECTION (non-suspend, synchronous approximation) =====
    private fun testConnectionSync() {
        // Simple sync check: just try to get the database instance
        // The actual suspend version does a real network test via .info/connected
        val db = getDatabase()
        if (db != null) {
            firebaseAvailable.set(true)
            lastConnectionError = null
        }
    }

    // ===== TEST CONNECTION (suspend, real network check) =====
    suspend fun testConnection(): Boolean = suspendCancellableCoroutine { cont ->
        val ref = getRef(".info/connected")
        if (ref == null) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.value as? Boolean ?: false
                firebaseAvailable.set(connected)
                if (!connected) lastConnectionError = "Firebase not connected"
                Log.i(TAG, "Firebase connection test: $connected")
                cont.resume(connected)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase connection test failed: ${error.message}")
                firebaseAvailable.set(false)
                lastConnectionError = error.message
                cont.resume(false)
            }
        })
    }
}
