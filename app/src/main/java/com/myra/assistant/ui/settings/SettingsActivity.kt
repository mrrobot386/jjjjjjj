package com.myra.assistant.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.myra.assistant.service.AccessibilityHelperService
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var userNameInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var personalityRadioGroup: RadioGroup
    private lateinit var radioGf: RadioButton
    private lateinit var radioProfessional: RadioButton
    private lateinit var radioAssistant: RadioButton
    private lateinit var accessibilityStatusText: TextView
    private lateinit var primeContactsRecycler: RecyclerView
    private lateinit var addPrimeContactBtn: Button
    private lateinit var saveSettingsBtn: Button
    private lateinit var backBtn: ImageButton
    private lateinit var accessibilityCard: View

    private val primeContacts = mutableListOf<PrimeContact>()
    private lateinit var primeAdapter: PrimeContactAdapter

    private val modelOptions = listOf(
        ModelItem("Native Audio (Human Voice) — DEFAULT", "models/gemini-2.5-flash-native-audio-preview-12-2025"),
        ModelItem("Flash Live (Fast)", "models/gemini-2.0-flash-live-001"),
        ModelItem("Pro Audio Dialog", "models/gemini-2.5-flash-preview-native-audio-dialog")
    )

    private val voiceOptions = listOf(
        VoiceItem("Aoede (Female)", "Aoede"),
        VoiceItem("Charon (Male)", "Charon"),
        VoiceItem("Kore (Female)", "Kore"),
        VoiceItem("Fenrir (Male)", "Fenrir"),
        VoiceItem("Puck (Male)", "Puck"),
        VoiceItem("Leda (Female)", "Leda"),
        VoiceItem("Orus (Male)", "Orus"),
        VoiceItem("Zephyr (Female)", "Zephyr")
    )

    data class ModelItem(val display: String, val modelId: String) {
        override fun toString(): String = display
    }

    data class VoiceItem(val display: String, val voiceId: String) {
        override fun toString(): String = display
    }

    data class PrimeContact(val name: String, val number: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initViews()
        loadPreferences()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun initViews() {
        apiKeyInput = findViewById(R.id.apiKeyInput)
        userNameInput = findViewById(R.id.userNameInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        personalityRadioGroup = findViewById(R.id.personalityRadioGroup)
        radioGf = findViewById(R.id.radioGf)
        radioProfessional = findViewById(R.id.radioProfessional)
        radioAssistant = findViewById(R.id.radioAssistant)
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        accessibilityCard = findViewById(R.id.accessibilityCard)
        primeContactsRecycler = findViewById(R.id.primeContactsRecycler)
        addPrimeContactBtn = findViewById(R.id.addPrimeContactBtn)
        saveSettingsBtn = findViewById(R.id.saveSettingsBtn)
        backBtn = findViewById(R.id.backBtn)

        backBtn.setOnClickListener { finish() }

        // Setup Spinners
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelOptions)
        modelSpinner.adapter = modelAdapter

        val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        voiceSpinner.adapter = voiceAdapter

        // Setup Prime Contacts Recycler
        primeAdapter = PrimeContactAdapter(primeContacts) { position ->
            primeContacts.removeAt(position)
            primeAdapter.notifyItemRemoved(position)
        }
        primeContactsRecycler.layoutManager = LinearLayoutManager(this)
        primeContactsRecycler.adapter = primeAdapter

        addPrimeContactBtn.setOnClickListener {
            showAddContactDialog()
        }

        accessibilityCard.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        saveSettingsBtn.setOnClickListener {
            savePreferences()
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = AccessibilityHelperService.isEnabled(this)
        if (isEnabled) {
            accessibilityStatusText.text = "Status: Enabled ✅"
            accessibilityStatusText.setTextColor(Color.parseColor("#00E676"))
        } else {
            accessibilityStatusText.text = "Status: Disabled ❌ (Tap to enable)"
            accessibilityStatusText.setTextColor(Color.parseColor("#FF1744"))
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)

        // API Key
        val savedKey = prefs.getString("api_key", "") ?: ""
        apiKeyInput.setText(savedKey)

        // User Name
        val savedName = prefs.getString("user_name", "Boss") ?: "Boss"
        userNameInput.setText(savedName)

        // Model
        val savedModel = prefs.getString("gemini_model", modelOptions[0].modelId)
        val modelIdx = modelOptions.indexOfFirst { it.modelId == savedModel }.coerceAtLeast(0)
        modelSpinner.setSelection(modelIdx)

        // Voice
        val savedVoice = prefs.getString("gemini_voice", voiceOptions[0].voiceId)
        val voiceIdx = voiceOptions.indexOfFirst { it.voiceId == savedVoice }.coerceAtLeast(0)
        voiceSpinner.setSelection(voiceIdx)

        // Personality
        when (prefs.getString("personality_mode", "GF")) {
            "Professional" -> radioProfessional.isChecked = true
            "Assistant" -> radioAssistant.isChecked = true
            else -> radioGf.isChecked = true
        }

        // Prime Contacts
        primeContacts.clear()
        val jsonStr = prefs.getString("prime_contacts_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val number = obj.optString("number", "")
                    if (name.isNotBlank() || number.isNotBlank()) {
                        primeContacts.add(PrimeContact(name, number))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Legacy Migration
            val legacyName = prefs.getString("prime_name", null)
            val legacyNumber = prefs.getString("prime_number", null)
            if (!legacyNumber.isNullOrBlank()) {
                primeContacts.add(PrimeContact(legacyName ?: "Close Friend", legacyNumber))
            }
        }
        primeAdapter.notifyDataSetChanged()
    }

    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        val nameInput = dialogView.findViewById<EditText>(R.id.dialogNameInput)
        val numberInput = dialogView.findViewById<EditText>(R.id.dialogNumberInput)

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("ADD") { _, _ ->
                val name = nameInput.text.toString().trim()
                val number = numberInput.text.toString().trim()
                if (name.isNotBlank() && number.isNotBlank()) {
                    primeContacts.add(PrimeContact(name, number))
                    primeAdapter.notifyItemInserted(primeContacts.size - 1)
                } else {
                    Toast.makeText(this, "Please enter both name and number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        editor.putString("api_key", apiKeyInput.text.toString().trim())
        editor.putString("user_name", userNameInput.text.toString().trim().ifBlank { "Boss" })

        val selectedModel = modelOptions[modelSpinner.selectedItemPosition].modelId
        editor.putString("gemini_model", selectedModel)

        val selectedVoice = voiceOptions[voiceSpinner.selectedItemPosition].voiceId
        editor.putString("gemini_voice", selectedVoice)

        val personality = when {
            radioProfessional.isChecked -> "Professional"
            radioAssistant.isChecked -> "Assistant"
            else -> "GF"
        }
        editor.putString("personality_mode", personality)

        // Save Prime Contacts JSON
        val array = JSONArray()
        for (contact in primeContacts) {
            val obj = JSONObject().apply {
                put("name", contact.name)
                put("number", contact.number)
            }
            array.put(obj)
        }
        editor.putString("prime_contacts_json", array.toString())

        editor.apply()

        Toast.makeText(this, "Restart app to apply changes", Toast.LENGTH_LONG).show()
        finish()
    }

    class PrimeContactAdapter(
        private val items: List<PrimeContact>,
        private val onDeleteClick: (Int) -> Unit
    ) : RecyclerView.Adapter<PrimeContactAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.nameText.text = item.name
            holder.numberText.text = item.number
            holder.deleteBtn.setOnClickListener {
                onDeleteClick(holder.adapterPosition)
            }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewById(R.id.primeItemName)
            val numberText: TextView = itemView.findViewById(R.id.primeItemNumber)
            val deleteBtn: ImageButton = itemView.findViewById(R.id.primeItemDelete)
        }
    }
}
