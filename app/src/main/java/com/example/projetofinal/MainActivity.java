package com.example.projetofinal;

// ANDROID CORE IMPORTS
import android.Manifest; // For permission constants
import android.content.Intent; // For launching other apps (camera, etc.)
import android.content.pm.PackageManager; // For checking app permissions
import android.os.Bundle; // For saving activity state
import android.os.Handler; // For scheduling code on main thread
import android.os.Looper; // For main thread reference
import android.provider.MediaStore; // For camera/video intents
import android.speech.tts.TextToSpeech; // For voice feedback
import android.util.Log; // For logging debug info
import android.widget.TextView; // For displaying status
import android.widget.Toast; // For user notifications

// ANDROIDX (SUPPORT LIBRARY) IMPORTS
import androidx.annotation.NonNull; // For null parameter checks
import androidx.appcompat.app.AppCompatActivity; // Base activity class
import androidx.core.app.ActivityCompat; // For permission handling
import androidx.core.content.ContextCompat; // For permission checking
import androidx.lifecycle.Lifecycle; // For activity state management

// JAVA STANDARD IMPORTS
import java.io.File; // For file operations
import java.io.IOException; // For file error handling
import java.util.Locale; // For language settings
import java.util.UUID; // For generating unique IDs

// POCKETSPHINX (SPEECH RECOGNITION) IMPORTS
import edu.cmu.pocketsphinx.Assets; // For managing audio model files
import edu.cmu.pocketsphinx.Hypothesis; // For speech recognition results
import edu.cmu.pocketsphinx.RecognitionListener; // For speech events
import edu.cmu.pocketsphinx.SpeechRecognizer; // Main recognition engine
import edu.cmu.pocketsphinx.SpeechRecognizerSetup; // For configuring recognizer

/**
 * MAIN ACTIVITY - Voice-Controlled Camera/Video App
 *
 * This app uses offline speech recognition (PocketSphinx) to control:
 * - Camera photos
 * - Video recording
 * - Basic messaging (placeholder)
 *
 * Key features:
 * - Works completely offline (no internet needed)
 * - Handles audio permissions
 * - Provides voice feedback via Text-to-Speech
 * - Robust error handling and recovery
 * - Prevents duplicate command processing
 */
public class MainActivity extends AppCompatActivity implements RecognitionListener, TextToSpeech.OnInitListener {

    // ==================== CONSTANTS & CONFIGURATION ====================

    // Logging tag for filtering logs in Android Studio
    private static final String TAG = "MainActivity_SLATE";

    // Name for the grammar search (matches the .gram file)
    private static final String GRAMMAR_SEARCH_NAME = "commands";

    // Permission request code (must be unique per permission request)
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    // ==================== TIMING & COOLDOWN SETTINGS ====================

    // Prevents processing the same command multiple times in quick succession
    private static final long COMMAND_PROCESSING_COOLDOWN_MS = 2500;

    // Maximum number of times to retry after errors
    private static final int MAX_ERROR_RESTART_ATTEMPTS = 3;

    // Initial delay before retrying after errors (in milliseconds)
    private static final long INITIAL_ERROR_RESTART_DELAY_MS = 1000;

    // Maximum delay between retries (prevents infinite rapid retries)
    private static final long MAX_ERROR_RESTART_DELAY_MS = 8000;

    // Delays for different scenarios
    private static final long ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS = 500;
    private static final long ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS = 1500;
    private static final long ON_TIMEOUT_RESTART_DELAY_MS = 500;

    // Watchdog timeout - maximum time to wait for final recognition result
    private static final long FINAL_RESULT_TIMEOUT_MS = 7000;

    // ==================== UI COMPONENTS ====================
    private TextView statusText; // Displays current app status

    // ==================== SPEECH RECOGNITION COMPONENTS ====================
    private SpeechRecognizer recognizer; // PocketSphinx recognition engine
    private boolean isRecognizerReady = false; // True when recognizer is configured
    private boolean isRecognizerInitializing = false; // True during initialization

    // ==================== TEXT-TO-SPEECH COMPONENTS ====================
    private TextToSpeech tts; // For voice feedback
    private boolean isTtsReady = false; // True when TTS is initialized

    // ==================== COMMAND PROCESSING VARIABLES ====================
    private String lastProcessedFinalCommand = ""; // Last processed command
    private long lastProcessedFinalCommandTime = 0; // When last command was processed
    private boolean isActionPending = false; // True when camera/video is active

    // ==================== ERROR HANDLING VARIABLES ====================
    private int errorRestartAttempts = 0; // Count of consecutive errors
    private Handler mainHandler; // For scheduling tasks on main thread

    // ==================== WATCHDOG TIMER VARIABLES ====================
    private Runnable finalResultTimeoutRunnable; // Timeout handler
    private boolean expectingFinalResult = false; // True when waiting for final result

    /**
     * ACTIVITY CREATION - Main entry point
     *
     * This method is called when the app starts. It:
     * 1. Sets up the user interface
     * 2. Initializes Text-to-Speech
     * 3. Checks microphone permissions
     * 4. Sets up error handling watchdogs
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load the XML layout file (res/layout/activity_main.xml)
        setContentView(R.layout.activity_main);

        // Find the status text view from the layout
        statusText = findViewById(R.id.statusText);

        // Show initial status message
        updateStatus(getString(R.string.status_initializing));

        // Create handler for scheduling tasks on the main UI thread
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize Text-to-Speech engine
        tts = new TextToSpeech(this, this);

        // Check if we already have microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Request permission from user
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            // Permission already granted - initialize recognizer
            Log.i(TAG, "Mic permission already granted on create.");
            initializeRecognizer();
        }

        // Set up watchdog timer for speech recognition timeouts
        finalResultTimeoutRunnable = () -> {
            if (expectingFinalResult) {
                Log.e(TAG, "WATCHDOG TIMEOUT: Sphinx did NOT call onResult() or onError() within " +
                        FINAL_RESULT_TIMEOUT_MS + "ms after onEndOfSpeech. Likely Pocketsphinx issue.");
                expectingFinalResult = false; // Reset flag first
                handleRecognitionErrorOrWatchdogTimeout("watchdog_timeout");
            }
        };
    }

    /**
     * PERMISSION REQUEST RESULT HANDLER
     *
     * Called when user responds to permission request dialog
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Check if this is our microphone permission request
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // User granted permission - initialize recognizer
                Log.i(TAG, "Mic permission GRANTED via dialog.");
                initializeRecognizer();
            } else {
                // User denied permission - show error
                Log.e(TAG, "Mic permission DENIED via dialog.");
                updateStatus(getString(R.string.status_permission_denied));
                Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * INITIALIZE SPEECH RECOGNIZER
     *
     * Sets up PocketSphinx engine with:
     * 1. Acoustic model (how speech sounds are recognized)
     * 2. Dictionary (how words are pronounced)
     * 3. Grammar file (what commands to listen for)
     *
     * Runs on background thread to avoid blocking UI
     */
    private void initializeRecognizer() {
        // Prevent multiple simultaneous initializations
        synchronized (this) {
            if (isRecognizerReady || isRecognizerInitializing) {
                Log.d(TAG, "Recognizer initialization already complete or in progress. Skipping.");
                return;
            }
            isRecognizerInitializing = true;
        }

        Log.i(TAG, "Initializing Recognizer...");
        updateStatus(getString(R.string.status_setup_recognizer));

        // Run initialization on background thread (file operations can be slow)
        new Thread(() -> {
            try {
                // Access app's asset files
                Assets assets = new Assets(MainActivity.this);

                // Copy asset files from APK to internal storage (where PocketSphinx can access them)
                File assetDir = assets.syncAssets();
                Log.i(TAG, "Assets synced to: " + assetDir.getAbsolutePath());

                // Define paths to model files
                File acousticModelDir = new File(assetDir, "en-us"); // Sound recognition model
                File dictionaryFile = new File(assetDir, "cmudict-en-us.dict"); // Word pronunciations
                File grammarFile = new File(assetDir, "commands.gram"); // Command definitions

                // Verify all required files exist
                if (!acousticModelDir.exists() || !acousticModelDir.isDirectory() || !new File(acousticModelDir, "mdef").exists()) {
                    throw new IOException("Acoustic model 'en-us' dir or critical 'mdef' file missing/invalid at " + acousticModelDir.getAbsolutePath());
                }
                if (!dictionaryFile.exists()) {
                    throw new IOException("Dictionary file missing: " + dictionaryFile.getAbsolutePath());
                }
                if (!grammarFile.exists()) {
                    throw new IOException("Grammar file 'commands.gram' missing: " + grammarFile.getAbsolutePath());
                }

                // Configure PocketSphinx with our model files
                recognizer = SpeechRecognizerSetup.defaultSetup()
                        .setAcousticModel(acousticModelDir)
                        .setDictionary(dictionaryFile)
                        .getRecognizer();

                if (recognizer == null) {
                    throw new RuntimeException("SpeechRecognizerSetup.getRecognizer() returned null. Setup failed.");
                }

                // Set this activity to receive recognition events
                recognizer.addListener(this);

                // Load our command grammar file
                recognizer.addGrammarSearch(GRAMMAR_SEARCH_NAME, grammarFile);
                Log.i(TAG, "Grammar search '" + GRAMMAR_SEARCH_NAME + "' added.");

                // Mark initialization as complete
                synchronized (MainActivity.this) {
                    isRecognizerReady = true;
                    isRecognizerInitializing = false;
                }

                // Reset error counter on successful initialization
                errorRestartAttempts = 0;
                Log.i(TAG, "Recognizer initialization successful.");

                // Start listening if activity is active and no actions pending
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) && !isActionPending) {
                    mainHandler.post(this::startRecognitionSafely);
                } else {
                    Log.i(TAG, "Recognizer initialized, but activity not resumed or action pending. Listening will start via onResume if appropriate.");
                }

            } catch (IOException e) {
                // Handle file access errors
                Log.e(TAG, "Recognizer initialization failed (IOException): " + e.getMessage(), e);
                handleRecognitionErrorOrWatchdogTimeout("init_io_exception");
                updateStatusOnUiThread(getString(R.string.status_error_recognizer_io, e.getMessage()));
                synchronized (MainActivity.this) {
                    isRecognizerReady = false;
                    isRecognizerInitializing = false;
                }
            } catch (Exception e) {
                // Handle any other unexpected errors
                Log.e(TAG, "Unexpected error during recognizer initialization: " + e.getMessage(), e);
                handleRecognitionErrorOrWatchdogTimeout("init_exception");
                updateStatusOnUiThread(getString(R.string.status_error_recognizer_unexpected));
                synchronized (MainActivity.this) {
                    isRecognizerReady = false;
                    isRecognizerInitializing = false;
                }
            }
        }).start();
    }

    /**
     * SAFELY START LISTENING
     *
     * Checks all conditions before starting speech recognition:
     * 1. Activity is active
     * 2. No actions pending (camera/video not active)
     * 3. Recognizer is ready
     * 4. Handles errors gracefully
     */
    private void startRecognitionSafely() {
        // Don't start if activity is not in foreground
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "startRecognitionSafely: Activity is not resumed. Aborting.");
            return;
        }

        // Don't start if camera/video is active
        if (isActionPending) {
            Log.w(TAG, "startRecognitionSafely: Action is pending. Aborting start.");
            return;
        }

        // Don't start if recognizer isn't ready
        if (!isRecognizerReady || recognizer == null) {
            Log.w(TAG, "startRecognitionSafely: Recognizer not ready or null.");
            updateStatus(getString(R.string.status_recognizer_not_ready));

            // Try to re-initialize if not already initializing
            if (!isRecognizerInitializing && !isRecognizerReady) {
                Log.w(TAG, "startRecognitionSafely: Recognizer not ready & not init. Attempting to re-initialize.");
                initializeRecognizer();
            }
            return;
        }

        try {
            // Stop any previous recognition first
            recognizer.stop();
            Log.d(TAG, "Recognizer stopped prior to startListening in startRecognitionSafely.");
        } catch (IllegalStateException e) {
            // Ignore if already stopped
            Log.w(TAG, "IllegalStateException during recognizer.stop() in startRecognitionSafely (often benign): " + e.getMessage());
        }

        // Cancel any pending timeout watchdogs
        cancelFinalResultTimeout();

        try {
            // Start listening for our predefined commands
            recognizer.startListening(GRAMMAR_SEARCH_NAME);
            Log.i(TAG, "Recognizer started listening for grammar: '" + GRAMMAR_SEARCH_NAME + "'.");
            updateStatus(getString(R.string.status_listening));
        } catch (Exception e){
            // Handle start listening errors
            Log.e(TAG, "Exception during startListening in startRecognitionSafely: " + e.getMessage());
            updateStatus(getString(R.string.status_error_starting_recognizer));
            scheduleRestartRecognition(INITIAL_ERROR_RESTART_DELAY_MS, "start_listening_exception");
        }
    }

    /**
     * STOP LISTENING SAFELY
     *
     * Stops speech recognition and cleans up resources
     */
    private void stopRecognition() {
        // Cancel any pending timeout
        cancelFinalResultTimeout();

        if (recognizer != null) {
            try {
                // Check if currently listening to our commands
                if (recognizer.getSearchName() != null && recognizer.getSearchName().equals(GRAMMAR_SEARCH_NAME)) {
                    recognizer.cancel(); // Cancel active recognition
                    Log.d(TAG, "Recognizer.cancel() called during stopRecognition.");
                }
                recognizer.stop(); // Stop the recognizer
                Log.i(TAG, "Recognizer stopped listening via stopRecognition().");
            } catch (IllegalStateException e) {
                // Ignore if already stopped
                Log.w(TAG, "IllegalStateException during recognizer.stop/cancel in stopRecognition (often benign): " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception during stopRecognition: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "stopRecognition called but recognizer was null.");
        }
    }

    /**
     * WATCHDOG TIMER MANAGEMENT
     *
     * These methods manage timeout detection for speech recognition
     */
    private void startFinalResultTimeout() {
        cancelFinalResultTimeout(); // Clear any existing timeout
        expectingFinalResult = true;
        Log.d(TAG, "Starting watchdog for final result (" + FINAL_RESULT_TIMEOUT_MS + "ms).");
        mainHandler.postDelayed(finalResultTimeoutRunnable, FINAL_RESULT_TIMEOUT_MS);
    }

    private void cancelFinalResultTimeout() {
        if (expectingFinalResult) {
            Log.d(TAG, "Cancelling watchdog for final result.");
        }
        mainHandler.removeCallbacks(finalResultTimeoutRunnable);
        expectingFinalResult = false;
    }

    // ==================== TEXT-TO-SPEECH METHODS ====================

    /**
     * TTS INITIALIZATION CALLBACK
     *
     * Called when Text-to-Speech engine is ready
     */
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Try to set US English language
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language (US English) is not supported or missing data.");
                Toast.makeText(this, "TTS language not available.", Toast.LENGTH_SHORT).show();
                isTtsReady = false;
            } else {
                Log.i(TAG, "TTS initialized successfully.");
                isTtsReady = true;
                // Announce readiness if recognizer is also ready
                if (isRecognizerReady && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    speak(getString(R.string.tts_system_ready));
                }
            }
        } else {
            Log.e(TAG, "TTS initialization FAILED with status: " + status);
            Toast.makeText(this, "TTS failed to initialize.", Toast.LENGTH_SHORT).show();
            isTtsReady = false;
        }
    }

    /**
     * SPEAK TEXT
     *
     * Safely speaks text using TTS with various checks
     */
    private void speak(String text) {
        if (tts != null && isTtsReady && text != null && !text.isEmpty() &&
                getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            // Generate unique ID for each speech utterance
            String utteranceId = this.hashCode() + "_" + UUID.randomUUID().toString();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        } else {
            // Log why speech didn't work
            if (!isTtsReady) Log.w(TAG, "TTS not ready, cannot speak: '" + text + "'");
            else if (tts == null) Log.w(TAG, "TTS is null, cannot speak: '" + text + "'");
            else if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) Log.w(TAG, "Activity not started, not speaking: " + text);
            else Log.w(TAG, "Text is null or empty when trying to speak.");
        }
    }

    // ==================== SPEECH RECOGNITION CALLBACKS ====================

    /**
     * SPEECH STARTED DETECTION
     *
     * Called when user starts speaking
     */
    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech detected.");
        cancelFinalResultTimeout(); // New speech started
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            updateStatus(getString(R.string.status_hearing_speech));
        }
    }

    /**
     * SPEECH ENDED DETECTION
     *
     * Called when user stops speaking
     */
    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "End of speech detected by recognizer.");
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            updateStatus(getString(R.string.status_processing_speech));
        }
        // Start timeout watchdog - expect result soon
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            startFinalResultTimeout();
        }
    }

    /**
     * PARTIAL RECOGNITION RESULTS
     *
     * Called as words are being recognized (real-time feedback)
     */
    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;

        if (hypothesis != null) {
            String partialText = hypothesis.getHypstr().toLowerCase(Locale.US).trim();
            if (!partialText.isEmpty()) {
                Log.d(TAG, "Partial result: " + partialText);
                updateStatus(getString(R.string.status_heard_partial, partialText));
            }
        }
    }

    /**
     * FINAL RECOGNITION RESULT
     *
     * Called when complete command has been processed
     * This is where command handling logic happens
     */
    @Override
    public void onResult(Hypothesis hypothesis) {
        cancelFinalResultTimeout(); // Got result, cancel watchdog

        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "onResult received while activity is not resumed. Ignored. Hyp: " + (hypothesis != null ? hypothesis.getHypstr() : "null"));
            return;
        }

        long restartDelayMs = ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS;
        boolean isCommandHandled = false;

        if (hypothesis != null) {
            String command = hypothesis.getHypstr().toLowerCase(Locale.US).trim();
            int score = hypothesis.getBestScore(); // Confidence score

            Log.i(TAG, "Final result received: '" + command + "' (Confidence: " + score + ")");

            if (!command.isEmpty()) {
                // Check if recognition confidence is high enough
                if (score > -7000) {
                    long currentTime = System.currentTimeMillis();
                    // Prevent processing same command multiple times quickly
                    if (!command.equals(lastProcessedFinalCommand) || (currentTime - lastProcessedFinalCommandTime > COMMAND_PROCESSING_COOLDOWN_MS)) {
                        lastProcessedFinalCommand = command;
                        lastProcessedFinalCommandTime = currentTime;

                        Log.i(TAG, "Handling validated final command (good confidence): '" + command + "'");
                        handleCommand(command);
                        isCommandHandled = true;
                        errorRestartAttempts = 0; // Reset error count on success
                    } else {
                        Log.i(TAG, "Duplicate final command '" + command + "' (good confidence) ignored due to cooldown.");
                    }
                } else {
                    // Confidence too low - ignore command
                    Log.w(TAG, "Command '" + command + "' REJECTED due to low confidence: " + score);
                    updateStatus(getString(R.string.status_no_clear_audio));
                    restartDelayMs = ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS;
                }
            } else {
                // Empty command string
                Log.i(TAG, "Final result was an empty string.");
                updateStatus(getString(R.string.status_no_clear_audio));
                restartDelayMs = ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS;
            }
        } else {
            // Null hypothesis
            Log.i(TAG, "Hypothesis was null in onResult.");
            updateStatus(getString(R.string.status_recognizer_issue_try_again));
            restartDelayMs = ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS;
        }

        // Restart listening if no action was started
        if (!isActionPending && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "onResult: No action pending. Scheduling restart. isCommandHandled: " + isCommandHandled);
            scheduleRestartRecognition(restartDelayMs, "onResult_completed_or_failed");
        } else if (isActionPending) {
            Log.d(TAG, "onResult: Action is pending after handleCommand. Not scheduling immediate restart. onResume will handle.");
        }
    }

    /**
     * RECOGNITION ERROR HANDLER
     */
    @Override
    public void onError(Exception e) {
        Log.e(TAG, "Recognition error: " + e.getMessage(), e);
        handleRecognitionErrorOrWatchdogTimeout("onError_callback");
    }

    /**
     * RECOGNITION TIMEOUT HANDLER
     */
    @Override
    public void onTimeout() {
        Log.w(TAG, "Recognition timeout (Sphinx internal).");
        handleRecognitionErrorOrWatchdogTimeout("onTimeout_callback");
    }

    /**
     * CENTRAL ERROR HANDLING
     *
     * Handles all types of recognition errors with automatic retry logic
     */
    private void handleRecognitionErrorOrWatchdogTimeout(String reason) {
        cancelFinalResultTimeout();

        // Update UI based on error type
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            if ("onError_callback".equals(reason)) {
                updateStatus(getString(R.string.status_error_recognition_generic));
            } else if ("onTimeout_callback".equals(reason)) {
                updateStatus(getString(R.string.status_timeout_listening_again));
            } else if ("watchdog_timeout".equals(reason)) {
                updateStatus(getString(R.string.status_error_recognizer_stuck));
            }
        }

        // Don't restart if activity is not active or action is pending
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) || isActionPending) {
            Log.w(TAG, "Error/Timeout ("+reason+"): Activity not resumed or action pending. Not restarting from here.");
            return;
        }

        // Handle retry logic with exponential backoff
        if (isRecognizerReady && !isFinishing() && !isDestroyed()) {
            if (errorRestartAttempts < MAX_ERROR_RESTART_ATTEMPTS || reason.startsWith("init_")) {
                if (!reason.startsWith("init_")) errorRestartAttempts++;

                long delay = INITIAL_ERROR_RESTART_DELAY_MS;
                // Exponential backoff for repeated errors
                if (!reason.startsWith("init_") && errorRestartAttempts > 1) {
                    delay = Math.min(INITIAL_ERROR_RESTART_DELAY_MS * (long)Math.pow(2, errorRestartAttempts - 1), MAX_ERROR_RESTART_DELAY_MS);
                } else if ("onTimeout_callback".equals(reason)){
                    delay = ON_TIMEOUT_RESTART_DELAY_MS;
                }

                Log.d(TAG, "Attempting to restart listening after " + reason + " (attempt " + errorRestartAttempts + ") with delay: " + delay + "ms");
                scheduleRestartRecognition(delay, reason + "_restart_attempt");
            } else {
                // Too many errors - give up
                Log.e(TAG, "Max error restart attempts reached for " + reason + ".");
                speak(getString(R.string.tts_error_voice_recognition_failed_permanently));
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                    updateStatus(getString(R.string.status_error_max_retries));
                }
            }
        } else {
            Log.w(TAG, "Cannot restart after "+reason+": Recognizer not ready or activity finishing/destroyed.");
            // Try re-initialization if recognizer isn't ready
            if (!isRecognizerInitializing && !isRecognizerReady && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Attempting to re-initialize recognizer after " + reason + " as it was not ready.");
                initializeRecognizer();
            }
        }
    }

    /**
     * SCHEDULE RECOGNITION RESTART
     *
     * Delays restart to allow system to recover
     */
    private void scheduleRestartRecognition(long delayMs, String reason) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "Not scheduling restart (" + reason + "): Activity not resumed at time of scheduling call.");
            return;
        }
        if (isActionPending) {
            Log.w(TAG, "Not scheduling restart (" + reason + "): Action is pending.");
            return;
        }

        if (isRecognizerReady && !isFinishing() && !isDestroyed()) {
            Log.i(TAG, "Scheduling restart of listening (" + reason + ") in " + delayMs + "ms.");
            mainHandler.postDelayed(() -> {
                // Check conditions again before actually restarting
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) &&
                        isRecognizerReady && !isFinishing() && !isDestroyed() && !isActionPending) {
                    Log.i(TAG, "Delayed restart (" + reason + ") of listening now executing.");
                    startRecognitionSafely();
                } else {
                    Log.w(TAG, "Delayed restart (" + reason + ") aborted: Conditions no longer met.");
                }
            }, delayMs);
        } else {
            Log.w(TAG, "Not scheduling restart (" + reason + "): Recognizer not ready or activity finishing/destroyed.");
            // Try re-initialization if needed
            if (!isRecognizerInitializing && !isRecognizerReady && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Attempting to re-initialize recognizer during scheduleRestart as it was not ready.");
                initializeRecognizer();
            }
        }
    }

    /**
     * COMMAND PROCESSING
     *
     * Maps recognized speech commands to actions
     */
    private void handleCommand(String commandText) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "handleCommand called but activity not resumed. Ignoring command: " + commandText);
            return;
        }

        Log.i(TAG, "Processing verified command in handleCommand: '" + commandText + "'");

        String processedCommand = commandText.toLowerCase(Locale.US).trim();
        boolean commandLeadsToPendingAction = false;

        // Map commands to actions
        switch (processedCommand) {
            case "take photo":
            case "snap photo":
            case "take picture":
            case "snap picture":
            case "photo":
            case "picture":
            case "open camera for photo":
            case "capture photo":
            case "capture picture":
                commandLeadsToPendingAction = true;
                isActionPending = true; // Mark that camera will be launched
                takePhoto();
                break;

            case "record video":
            case "start video":
            case "video":
            case "capture video":
            case "film video":
            case "start recording":
            case "begin video":
                commandLeadsToPendingAction = true;
                isActionPending = true; // Mark that video will be launched
                recordVideo();
                break;

            case "send message":
            case "send text":
            case "message":
            case "text":
            case "write message":
            case "compose message":
                speak(getString(R.string.tts_message_placeholder));
                updateStatus(getString(R.string.status_message_command));
                break;

            default:
                // Unknown command
                Log.w(TAG, "Unrecognized final command: '" + processedCommand + "'");
                String ttsMessage = getString(R.string.tts_unrecognized_command, processedCommand);
                speak(ttsMessage);
                String formattedStatus = getString(R.string.status_unrecognized_command, processedCommand);
                updateStatus(formattedStatus);
                break;
        }
    }

    /**
     * LAUNCH CAMERA FOR PHOTOS
     */
    private void takePhoto() {
        Log.i(TAG, "takePhoto action initiated.");
        stopRecognition(); // Stop listening before launching camera

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            speak(getString(R.string.tts_opening_camera_photo));
            updateStatus(getString(R.string.status_opening_camera));
            startActivity(intent); // Launch camera app
        } else {
            // No camera app available
            String noCameraAppMessage = getString(R.string.toast_no_camera_app);
            Log.e(TAG, noCameraAppMessage);
            speak(noCameraAppMessage);
            Toast.makeText(this, noCameraAppMessage, Toast.LENGTH_SHORT).show();
            updateStatus(getString(R.string.status_error_no_camera));
            isActionPending = false; // Reset flag
            // Restart listening since action failed
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                scheduleRestartRecognition(ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS, "takePhoto_failed_restart");
            }
        }
    }

    /**
     * LAUNCH CAMERA FOR VIDEO
     */
    private void recordVideo() {
        Log.i(TAG, "recordVideo action initiated.");
        stopRecognition(); // Stop listening before launching video

        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            speak(getString(R.string.tts_starting_video_record));
            updateStatus(getString(R.string.status_recording_video));
            startActivity(intent); // Launch video app
        } else {
            // No video app available
            String noVideoAppMessage = getString(R.string.toast_no_video_app);
            Log.e(TAG, noVideoAppMessage);
            speak(noVideoAppMessage);
            Toast.makeText(this, noVideoAppMessage, Toast.LENGTH_SHORT).show();
            updateStatus(getString(R.string.status_error_no_video_recorder));
            isActionPending = false; // Reset flag
            // Restart listening since action failed
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                scheduleRestartRecognition(ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS, "recordVideo_failed_restart");
            }
        }
    }

    /**
     * UPDATE STATUS TEXT (UI THREAD SAFE)
     */
    private void updateStatus(final String message) {
        if (statusText == null) return;

        // Ensure we're on UI thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusText.setText(message);
        } else {
            mainHandler.post(() -> statusText.setText(message));
        }
    }

    private void updateStatusOnUiThread(final String message) {
        if (statusText == null) return;
        mainHandler.post(() -> statusText.setText(message));
    }

    // ==================== ACTIVITY LIFECYCLE METHODS ====================

    /**
     * ACTIVITY RESUMED
     *
     * Called when app comes to foreground
     * Starts or restarts speech recognition
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called. isActionPending: " + isActionPending +
                ", isRecognizerReady: " + isRecognizerReady +
                ", isRecognizerInitializing: " + isRecognizerInitializing);

        if (isActionPending) {
            // Returning from camera/video - restart listening after delay
            final boolean returningFromAction = true;
            isActionPending = false;
            Log.i(TAG, "onResume: Returning from a pending action. Will attempt restart listening after delay.");

            mainHandler.postDelayed(() -> {
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    Log.i(TAG, "onResume: Delay finished after action, attempting to startRecognitionSafely.");
                    startRecognitionSafely();
                } else {
                    Log.w(TAG, "onResume: Activity not resumed when delayed restart after action was to execute.");
                }
            }, 500);

        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            // Normal resume - start/restart recognition
            if (!isRecognizerReady && !isRecognizerInitializing) {
                Log.i(TAG, "onResume: Recognizer not ready and not initializing. Initializing now.");
                initializeRecognizer();
            } else if (isRecognizerReady) {
                Log.i(TAG, "onResume: Recognizer ready. Ensuring listening is active.");
                startRecognitionSafely();
            } else {
                Log.i(TAG, "onResume: Recognizer is currently initializing. Will start when ready via its own callback.");
            }
        } else {
            Log.w(TAG, "onResume: Microphone permission not granted. Cannot start recognizer.");
            updateStatus(getString(R.string.status_permission_needed_resume));
        }
    }

    /**
     * ACTIVITY PAUSED
     *
     * Called when app goes to background
     * Stops speech recognition and cleans up
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause called. isActionPending: " + isActionPending +
                " (reflects state BEFORE this pause was triggered)");

        // Clear all pending operations
        mainHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Cleared all pending Runnables from mainHandler in onPause.");

        cancelFinalResultTimeout();

        // Stop recognition (safe to call multiple times)
        stopRecognition();

        // Stop TTS if speaking
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
            Log.d(TAG, "TTS stopped in onPause because it was speaking.");
        }
        // Note: isActionPending is NOT reset here - used by onResume to detect return from camera
    }

    /**
     * ACTIVITY DESTROYED
     *
     * Called when app is closing
     * Releases all resources
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy called. Releasing resources.");

        // Clean up everything
        cancelFinalResultTimeout();
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        // Shutdown TTS
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isTtsReady = false;
            Log.d(TAG, "TTS engine shut down.");
        }

        // Shutdown recognizer on background thread (can be slow)
        if (recognizer != null) {
            final SpeechRecognizer localRecognizer = recognizer;
            recognizer = null;
            isRecognizerReady = false;
            isRecognizerInitializing = false;

            new Thread(() -> {
                try {
                    Log.d(TAG, "Attempting recognizer shutdown on background thread...");
                    localRecognizer.cancel();
                    localRecognizer.shutdown();
                    Log.i(TAG, "PocketSphinx recognizer shut down successfully on background thread.");
                } catch (Exception e) {
                    Log.e(TAG, "Exception during recognizer shutdown on background thread: " + e.getMessage(), e);
                }
            }).start();
        }
    }
}
