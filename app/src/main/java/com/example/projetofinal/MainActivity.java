package com.example.projetofinal;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
// import android.os.Build; // Not strictly needed unless using Build.VERSION for specific checks
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import edu.cmu.pocketsphinx.Assets;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.RecognitionListener;
import edu.cmu.pocketsphinx.SpeechRecognizer;
import edu.cmu.pocketsphinx.SpeechRecognizerSetup;

public class MainActivity extends AppCompatActivity implements RecognitionListener, TextToSpeech.OnInitListener {

    private static final String TAG = "MainActivity_SLATE";

    private SpeechRecognizer recognizer;
    private static final String GRAMMAR_SEARCH_NAME = "commands";
    private boolean isRecognizerReady = false;
    private boolean isRecognizerInitializing = false;
    private Handler mainHandler;

    private TextView statusText;

    private TextToSpeech tts;
    private boolean isTtsReady = false;

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private String lastProcessedFinalCommand = "";
    private long lastProcessedFinalCommandTime = 0;
    private static final long COMMAND_PROCESSING_COOLDOWN_MS = 2500; // Time to ignore duplicate commands

    private int errorRestartAttempts = 0;
    private static final int MAX_ERROR_RESTART_ATTEMPTS = 3;
    private static final long INITIAL_ERROR_RESTART_DELAY_MS = 1000; // Initial delay for error restart
    private static final long MAX_ERROR_RESTART_DELAY_MS = 8000;   // Max delay for error restart

    // Delays for restarting recognition under different scenarios
    private static final long ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS = 500; // After a non-action command
    private static final long ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS = 1500;
    private static final long ON_TIMEOUT_RESTART_DELAY_MS = 500; // Sphinx internal timeout

    private boolean isActionPending = false; // True if we launched camera/video

    private static final long FINAL_RESULT_TIMEOUT_MS = 7000; // Watchdog: Time to wait for onResult after onEndOfSpeech
    private Runnable finalResultTimeoutRunnable;
    private boolean expectingFinalResult = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        updateStatus(getString(R.string.status_initializing));

        mainHandler = new Handler(Looper.getMainLooper());
        tts = new TextToSpeech(this, this);

        // Permissions Check
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            Log.i(TAG, "Mic permission already granted on create.");
            initializeRecognizer();
        }

        // Watchdog runnable
        finalResultTimeoutRunnable = () -> {
            if (expectingFinalResult) {
                Log.e(TAG, "WATCHDOG TIMEOUT: Sphinx did NOT call onResult() or onError() within " +
                        FINAL_RESULT_TIMEOUT_MS + "ms after onEndOfSpeech. Likely Pocketsphinx issue.");
                expectingFinalResult = false; // Reset flag first

                // If this timeout occurs, it implies a problem with Sphinx not giving a result.
                // We should stop the current recognition attempt and schedule a restart.
                // No need to call stopRecognition() directly from here as it might also try to cancel this.
                // Instead, behave like an error occurred.
                handleRecognitionErrorOrWatchdogTimeout("watchdog_timeout");
            }
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Mic permission GRANTED via dialog.");
                initializeRecognizer();
            } else {
                Log.e(TAG, "Mic permission DENIED via dialog.");
                updateStatus(getString(R.string.status_permission_denied));
                Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void initializeRecognizer() {
        synchronized (this) { // Synchronize access to isRecognizerReady and isRecognizerInitializing
            if (isRecognizerReady || isRecognizerInitializing) {
                Log.d(TAG, "Recognizer initialization already complete or in progress. Skipping.");
                return;
            }
            isRecognizerInitializing = true;
        }
        Log.i(TAG, "Initializing Recognizer...");
        updateStatus(getString(R.string.status_setup_recognizer));

        new Thread(() -> { // Perform asset sync and setup on a background thread
            try {
                Assets assets = new Assets(MainActivity.this);
                File assetDir = assets.syncAssets(); // This copies files from assets/sync to internal storage
                Log.i(TAG, "Assets synced to: " + assetDir.getAbsolutePath());

                // Define paths to model, dictionary, and grammar files
                File acousticModelDir = new File(assetDir, "en-us"); // Expects "en-us" FOLDER
                File dictionaryFile = new File(assetDir, "cmudict-en-us.dict"); // Expects .dict FILE
                File grammarFile = new File(assetDir, "commands.gram"); // Expects .gram FILE
                // **IMPORTANT**: If commands.gram is INSIDE en-us, use: new File(acousticModelDir, "commands.gram");

                // Validate that all necessary files/directories exist
                if (!acousticModelDir.exists() || !acousticModelDir.isDirectory() || !new File(acousticModelDir, "mdef").exists()) {
                    throw new IOException("Acoustic model 'en-us' dir or critical 'mdef' file missing/invalid at " + acousticModelDir.getAbsolutePath());
                }
                if (!dictionaryFile.exists()) {
                    throw new IOException("Dictionary file missing: " + dictionaryFile.getAbsolutePath());
                }
                if (!grammarFile.exists()) {
                    throw new IOException("Grammar file 'commands.gram' missing: " + grammarFile.getAbsolutePath());
                }

                recognizer = SpeechRecognizerSetup.defaultSetup()
                        .setAcousticModel(acousticModelDir)
                        .setDictionary(dictionaryFile)
                        // .setBoolean("-remove_noise", true) // Example: experiment if available
                        .getRecognizer();

                if (recognizer == null) {
                    throw new RuntimeException("SpeechRecognizerSetup.getRecognizer() returned null. Setup failed.");
                }

                recognizer.addListener(this);
                recognizer.addGrammarSearch(GRAMMAR_SEARCH_NAME, grammarFile);
                Log.i(TAG, "Grammar search '" + GRAMMAR_SEARCH_NAME + "' added.");

                synchronized (MainActivity.this) {
                    isRecognizerReady = true;
                    isRecognizerInitializing = false;
                }
                errorRestartAttempts = 0; // Reset error count on successful initialization
                Log.i(TAG, "Recognizer initialization successful.");

                // If activity is already resumed, start listening. Otherwise, onResume will handle it.
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) && !isActionPending) {
                    mainHandler.post(this::startRecognitionSafely);
                } else {
                    Log.i(TAG, "Recognizer initialized, but activity not resumed or action pending. Listening will start via onResume if appropriate.");
                }

            } catch (IOException e) {
                Log.e(TAG, "Recognizer initialization failed (IOException): " + e.getMessage(), e);
                handleRecognitionErrorOrWatchdogTimeout("init_io_exception");
                updateStatusOnUiThread(getString(R.string.status_error_recognizer_io, e.getMessage()));
                synchronized (MainActivity.this) {
                    isRecognizerReady = false;
                    isRecognizerInitializing = false;
                }
            } catch (Exception e) { // Catch any other unexpected exceptions
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

    private void startRecognitionSafely() {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "startRecognitionSafely: Activity is not resumed. Aborting.");
            return;
        }
        if (isActionPending) {
            Log.w(TAG, "startRecognitionSafely: Action is pending. Aborting start.");
            return;
        }
        if (!isRecognizerReady || recognizer == null) {
            Log.w(TAG, "startRecognitionSafely: Recognizer not ready or null.");
            updateStatus(getString(R.string.status_recognizer_not_ready));
            // If not initializing and not ready, attempt re-initialization
            if (!isRecognizerInitializing && !isRecognizerReady) {
                Log.w(TAG, "startRecognitionSafely: Recognizer not ready & not init. Attempting to re-initialize.");
                initializeRecognizer();
            }
            return;
        }

        try {
            // It's good practice to stop before starting, even if it seems redundant,
            // to ensure any previous state is cleared.
            recognizer.stop();
            Log.d(TAG, "Recognizer stopped prior to startListening in startRecognitionSafely.");
        } catch (IllegalStateException e) {
            // This can happen if recognizer was already stopped or in a state where stop isn't allowed.
            // Usually safe to ignore if the next step is startListening.
            Log.w(TAG, "IllegalStateException during recognizer.stop() in startRecognitionSafely (often benign): " + e.getMessage());
        }

        cancelFinalResultTimeout(); // Cancel any pending watchdog
        try {
            recognizer.startListening(GRAMMAR_SEARCH_NAME);
            Log.i(TAG, "Recognizer started listening for grammar: '" + GRAMMAR_SEARCH_NAME + "'.");
            updateStatus(getString(R.string.status_listening));
        } catch (Exception e){
            Log.e(TAG, "Exception during startListening in startRecognitionSafely: " + e.getMessage());
            updateStatus(getString(R.string.status_error_starting_recognizer));
            // Consider scheduling a restart or re-initialization here if startListening fails critically
            scheduleRestartRecognition(INITIAL_ERROR_RESTART_DELAY_MS, "start_listening_exception");
        }
    }

    private void stopRecognition() {
        cancelFinalResultTimeout(); // Always cancel watchdog when stopping
        if (recognizer != null) {
            try {
                // Check if it's currently listening to the search we want to stop/cancel
                if (recognizer.getSearchName() != null && recognizer.getSearchName().equals(GRAMMAR_SEARCH_NAME)) {
                    recognizer.cancel(); // Cancel active search first
                    Log.d(TAG, "Recognizer.cancel() called during stopRecognition.");
                }
                recognizer.stop(); // Then stop the recognizer
                Log.i(TAG, "Recognizer stopped listening via stopRecognition().");
            } catch (IllegalStateException e) {
                // This can happen if it's already stopped or in an invalid state.
                Log.w(TAG, "IllegalStateException during recognizer.stop/cancel in stopRecognition (often benign): " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception during stopRecognition: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "stopRecognition called but recognizer was null.");
        }
    }

    private void startFinalResultTimeout() {
        cancelFinalResultTimeout(); // Ensure no previous one is running
        expectingFinalResult = true;
        Log.d(TAG, "Starting watchdog for final result (" + FINAL_RESULT_TIMEOUT_MS + "ms).");
        mainHandler.postDelayed(finalResultTimeoutRunnable, FINAL_RESULT_TIMEOUT_MS);
    }

    private void cancelFinalResultTimeout() {
        if (expectingFinalResult) { // Log only if we were actively expecting
            Log.d(TAG, "Cancelling watchdog for final result.");
        }
        mainHandler.removeCallbacks(finalResultTimeoutRunnable);
        expectingFinalResult = false;
    }


    @Override
    public void onInit(int status) { // TTS onInit
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language (US English) is not supported or missing data.");
                Toast.makeText(this, "TTS language not available.", Toast.LENGTH_SHORT).show();
                isTtsReady = false;
            } else {
                Log.i(TAG, "TTS initialized successfully.");
                isTtsReady = true;
                // Consider speaking a "ready" message here if recognizer is also ready
                if (isRecognizerReady && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    speak(getString(R.string.tts_system_ready)); // Using your existing string
                }
            }
        } else {
            Log.e(TAG, "TTS initialization FAILED with status: " + status);
            Toast.makeText(this, "TTS failed to initialize.", Toast.LENGTH_SHORT).show();
            isTtsReady = false;
        }
    }

    private void speak(String text) {
        if (tts != null && isTtsReady && text != null && !text.isEmpty() &&
                getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) { // Check if activity is at least started
            String utteranceId = this.hashCode() + "_" + UUID.randomUUID().toString(); // Unique ID for each speech
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        } else {
            if (!isTtsReady) Log.w(TAG, "TTS not ready, cannot speak: '" + text + "'");
            else if (tts == null) Log.w(TAG, "TTS is null, cannot speak: '" + text + "'");
            else if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) Log.w(TAG, "Activity not started, not speaking: " + text);
            else Log.w(TAG, "Text is null or empty when trying to speak.");
        }
    }

    // --- RecognitionListener Callbacks ---
    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech detected.");
        cancelFinalResultTimeout(); // New speech started, cancel any pending watchdog for previous utterance
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            updateStatus(getString(R.string.status_hearing_speech));
        }
    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "End of speech detected by recognizer.");
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            updateStatus(getString(R.string.status_processing_speech));
        }
        // After end of speech, we expect onResult or onError. Start the watchdog.
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) { // Only if activity is active
            startFinalResultTimeout();
        }
    }

    @Override
    public void onPartialResult(Hypothesis hypothesis) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return; // Ignore if not active

        if (hypothesis != null) {
            String partialText = hypothesis.getHypstr().toLowerCase(Locale.US).trim();
            if (!partialText.isEmpty()) {
                Log.d(TAG, "Partial result: " + partialText);
                updateStatus(getString(R.string.status_heard_partial, partialText));
            }
        }
    }

    @Override
    public void onResult(Hypothesis hypothesis) {
        cancelFinalResultTimeout(); // We got a result (or null hypothesis), cancel watchdog

        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "onResult received while activity is not resumed. Ignored. Hyp: " + (hypothesis != null ? hypothesis.getHypstr() : "null"));
            return;
        }

        long restartDelayMs = ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS; // Default for non-action commands
        boolean isCommandHandled = false; // Flag to track if handleCommand was called

        if (hypothesis != null) {
            String command = hypothesis.getHypstr().toLowerCase(Locale.US).trim();
            int score = hypothesis.getBestScore(); // Get the confidence score
            Log.i(TAG, "Final result received: '" + command + "' (Confidence: " + score + ")");

            if (!command.isEmpty()) {
                // Check confidence score FIRST
                if (score > -7000) { // Confidence threshold (adjust as needed)
                    long currentTime = System.currentTimeMillis();
                    // Check cooldown for the same command
                    if (!command.equals(lastProcessedFinalCommand) || (currentTime - lastProcessedFinalCommandTime > COMMAND_PROCESSING_COOLDOWN_MS)) {
                        lastProcessedFinalCommand = command;
                        lastProcessedFinalCommandTime = currentTime;

                        Log.i(TAG, "Handling validated final command (good confidence): '" + command + "'");
                        handleCommand(command); // This will set isActionPending if needed
                        isCommandHandled = true; // Mark that a command was actually handled
                        errorRestartAttempts = 0; // Reset error count on valid, handled command
                    } else {
                        Log.i(TAG, "Duplicate final command '" + command + "' (good confidence) ignored due to cooldown.");
                        // Even if duplicate, it was a valid recognition, so use shorter restart delay
                        // or rely on the default restartDelayMs which is for non-action commands
                    }
                } else { // Command recognized but confidence is TOO LOW
                    Log.w(TAG, "Command '" + command + "' REJECTED due to low confidence: " + score);
                    updateStatus(getString(R.string.status_no_clear_audio)); // Or a more specific "low confidence" string
                    restartDelayMs = ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS; // Treat as unclear/failed recognition
                }
            } else { // Hypothesis string is EMPTY
                Log.i(TAG, "Final result was an empty string.");
                updateStatus(getString(R.string.status_no_clear_audio));
                restartDelayMs = ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS;
            }
        } else { // Hypothesis object itself is NULL
            Log.i(TAG, "Hypothesis was null in onResult.");
            updateStatus(getString(R.string.status_recognizer_issue_try_again));
            restartDelayMs = ON_RESULT_EMPTY_NULL_HYPOTHESIS_RESTART_DELAY_MS;
        }

        // Schedule restart only if no action is pending (camera/video not launched by handleCommand)
        // and the activity is still resumed.
        if (!isActionPending && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "onResult: No action pending. Scheduling restart. isCommandHandled: " + isCommandHandled);
            scheduleRestartRecognition(restartDelayMs, "onResult_completed_or_failed");
        } else if (isActionPending) {
            Log.d(TAG, "onResult: Action is pending after handleCommand. Not scheduling immediate restart. onResume will handle.");
        }
    }


    @Override
    public void onError(Exception e) {
        Log.e(TAG, "Recognition error: " + e.getMessage(), e);
        // No need to cancel watchdog here, as handleRecognitionErrorOrWatchdogTimeout will do it.
        handleRecognitionErrorOrWatchdogTimeout("onError_callback");
    }

    @Override
    public void onTimeout() { // This is Sphinx's internal timeout (e.g., no speech for a while)
        Log.w(TAG, "Recognition timeout (Sphinx internal).");
        // No need to cancel watchdog here, as handleRecognitionErrorOrWatchdogTimeout will do it.
        handleRecognitionErrorOrWatchdogTimeout("onTimeout_callback");
    }


    private void handleRecognitionErrorOrWatchdogTimeout(String reason) {
        cancelFinalResultTimeout(); // Always cancel any active watchdog first

        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            if ("onError_callback".equals(reason)) {
                updateStatus(getString(R.string.status_error_recognition_generic));
            } else if ("onTimeout_callback".equals(reason)) {
                updateStatus(getString(R.string.status_timeout_listening_again));
            } else if ("watchdog_timeout".equals(reason)) {
                updateStatus(getString(R.string.status_error_recognizer_stuck));
            } else if (reason.startsWith("init_")) {
                // Status already updated by initializeRecognizer for init failures
            }
        }

        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) || isActionPending) {
            Log.w(TAG, "Error/Timeout ("+reason+"): Activity not resumed or action pending. Not restarting from here.");
            return;
        }

        if (isRecognizerReady && !isFinishing() && !isDestroyed()) {
            if (errorRestartAttempts < MAX_ERROR_RESTART_ATTEMPTS || reason.startsWith("init_")) { // Always try to restart for init errors
                if (!reason.startsWith("init_")) errorRestartAttempts++; // Don't count init errors towards general max

                long delay = INITIAL_ERROR_RESTART_DELAY_MS;
                if (!reason.startsWith("init_") && errorRestartAttempts > 1) { // Apply backoff for subsequent runtime errors
                    delay = Math.min(INITIAL_ERROR_RESTART_DELAY_MS * (long)Math.pow(2, errorRestartAttempts - 1), MAX_ERROR_RESTART_DELAY_MS);
                } else if ("onTimeout_callback".equals(reason)){ // Quicker restart for simple timeout
                    delay = ON_TIMEOUT_RESTART_DELAY_MS;
                }

                Log.d(TAG, "Attempting to restart listening after " + reason + " (attempt " + errorRestartAttempts + ") with delay: " + delay + "ms");
                scheduleRestartRecognition(delay, reason + "_restart_attempt");
            } else {
                Log.e(TAG, "Max error restart attempts reached for " + reason + ".");
                speak(getString(R.string.tts_error_voice_recognition_failed_permanently));
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                    updateStatus(getString(R.string.status_error_max_retries));
                }
            }
        } else {
            Log.w(TAG, "Cannot restart after "+reason+": Recognizer not ready or activity finishing/destroyed.");
            if (!isRecognizerInitializing && !isRecognizerReady && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Attempting to re-initialize recognizer after " + reason + " as it was not ready.");
                initializeRecognizer(); // Try full re-initialization if recognizer wasn't even ready
            }
        }
    }


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
            // Remove any previously scheduled restarts for the same reason to avoid multiple stacked restarts
            // This requires a more complex way to manage Runnables if we want to cancel specific ones.
            // For now, postDelayed is simple. If issues arise, consider a single restartRunnable variable.
            mainHandler.postDelayed(() -> {
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) &&
                        isRecognizerReady && !isFinishing() && !isDestroyed() && !isActionPending) {
                    Log.i(TAG, "Delayed restart (" + reason + ") of listening now executing.");
                    startRecognitionSafely();
                } else {
                    Log.w(TAG, "Delayed restart (" + reason + ") aborted: Conditions no longer met. isActionPending: " + isActionPending +
                            ", isResumed: " + getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED) +
                            ", isRecognizerReady: " + isRecognizerReady);
                }
            }, delayMs);
        } else {
            Log.w(TAG, "Not scheduling restart (" + reason + "): Recognizer not ready or activity finishing/destroyed.");
            // If recognizer isn't ready, and we're not initializing, try to re-initialize.
            if (!isRecognizerInitializing && !isRecognizerReady && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Attempting to re-initialize recognizer during scheduleRestart as it was not ready.");
                initializeRecognizer();
            }
        }
    }


    private void handleCommand(String commandText) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            Log.w(TAG, "handleCommand called but activity not resumed. Ignoring command: " + commandText);
            // isActionPending should not be modified here if activity is not resumed
            return;
        }
        Log.i(TAG, "Processing verified command in handleCommand: '" + commandText + "'");

        String processedCommand = commandText.toLowerCase(Locale.US).trim();
        boolean commandLeadsToPendingAction = false;

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
                // Important: Set isActionPending BEFORE calling takePhoto, which stops recognition
                isActionPending = true;
                takePhoto(); // takePhoto itself will call stopRecognition() before launching intent
                break;

            case "record video":
            case "start video":
            case "video":
            case "capture video":
            case "film video":
            case "start recording":
            case "begin video":
                commandLeadsToPendingAction = true;
                isActionPending = true;
                recordVideo(); // recordVideo itself will call stopRecognition()
                break;

            case "send message":
            case "send text":
            case "message":
            case "text":
            case "write message":
            case "compose message":
                speak(getString(R.string.tts_message_placeholder));
                updateStatus(getString(R.string.status_message_command));
                // isActionPending remains false as this is not an external activity
                break;

            default:
                Log.w(TAG, "Unrecognized final command: '" + processedCommand + "'");
                String ttsMessage = getString(R.string.tts_unrecognized_command, processedCommand);
                speak(ttsMessage);
                String formattedStatus = getString(R.string.status_unrecognized_command, processedCommand);
                updateStatus(formattedStatus);
                // isActionPending remains false
                break;
        }
        // If the command did NOT lead to a pending action (like camera),
        // and we are resumed, schedule a restart of listening.
        // This is handled by the onResult logic that calls this.
    }

    private void takePhoto() {
        Log.i(TAG, "takePhoto action initiated.");
        // isActionPending should have been set true by handleCommand
        stopRecognition(); // Crucial: Stop recognizer BEFORE launching external activity

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            speak(getString(R.string.tts_opening_camera_photo));
            updateStatus(getString(R.string.status_opening_camera));
            startActivity(intent); // This will trigger onPause
        } else {
            String noCameraAppMessage = getString(R.string.toast_no_camera_app);
            Log.e(TAG, noCameraAppMessage);
            speak(noCameraAppMessage);
            Toast.makeText(this, noCameraAppMessage, Toast.LENGTH_SHORT).show();
            updateStatus(getString(R.string.status_error_no_camera));
            isActionPending = false; // Reset flag as action failed
            // If action failed, try to restart listening
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                scheduleRestartRecognition(ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS, "takePhoto_failed_restart");
            }
        }
    }

    private void recordVideo() {
        Log.i(TAG, "recordVideo action initiated.");
        // isActionPending should have been set true by handleCommand
        stopRecognition(); // Crucial: Stop recognizer BEFORE launching external activity

        Intent intent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            speak(getString(R.string.tts_starting_video_record));
            updateStatus(getString(R.string.status_recording_video));
            startActivity(intent); // This will trigger onPause
        } else {
            String noVideoAppMessage = getString(R.string.toast_no_video_app);
            Log.e(TAG, noVideoAppMessage);
            speak(noVideoAppMessage);
            Toast.makeText(this, noVideoAppMessage, Toast.LENGTH_SHORT).show();
            updateStatus(getString(R.string.status_error_no_video_recorder));
            isActionPending = false; // Reset flag as action failed
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                scheduleRestartRecognition(ON_RESULT_VALID_COMMAND_NO_ACTION_RESTART_DELAY_MS, "recordVideo_failed_restart");
            }
        }
    }

    private void updateStatus(final String message) {
        if (statusText == null) return;
        // Ensure UI updates are on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusText.setText(message);
        } else {
            mainHandler.post(() -> statusText.setText(message));
        }
    }

    private void updateStatusOnUiThread(final String message) { // Specifically for calls from background threads
        if (statusText == null) return;
        mainHandler.post(() -> statusText.setText(message));
    }

    // --- Activity Lifecycle Callbacks ---
    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called. isActionPending: " + isActionPending +
                ", isRecognizerReady: " + isRecognizerReady +
                ", isRecognizerInitializing: " + isRecognizerInitializing);

        if (isActionPending) {
            // We are returning from an action (camera/video).
            // isActionPending was true, reset it now.
            final boolean returningFromAction = true;
            isActionPending = false; // Reset immediately
            Log.i(TAG, "onResume: Returning from a pending action. Will attempt restart listening after delay.");
            // Delay slightly to allow the external app to fully close and release resources,
            // and for our activity to be fully resumed.
            mainHandler.postDelayed(() -> {
                if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
                    Log.i(TAG, "onResume: Delay finished after action, attempting to startRecognitionSafely.");
                    startRecognitionSafely();
                } else {
                    Log.w(TAG, "onResume: Activity not resumed when delayed restart after action was to execute.");
                }
            }, 500); // Delay in ms

        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            // Not returning from our action, check recognizer state
            if (!isRecognizerReady && !isRecognizerInitializing) {
                Log.i(TAG, "onResume: Recognizer not ready and not initializing. Initializing now.");
                initializeRecognizer();
            } else if (isRecognizerReady) {
                // Recognizer is ready, ensure it's listening
                Log.i(TAG, "onResume: Recognizer ready. Ensuring listening is active.");
                // errorRestartAttempts = 0; // Reset error count on a "normal" resume where we start listening
                startRecognitionSafely(); // This will handle if it's already listening or needs starting
            } else { // isRecognizerInitializing == true
                Log.i(TAG, "onResume: Recognizer is currently initializing. Will start when ready via its own callback.");
            }
        } else {
            Log.w(TAG, "onResume: Microphone permission not granted. Cannot start recognizer.");
            updateStatus(getString(R.string.status_permission_needed_resume));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "onPause called. isActionPending: " + isActionPending +
                " (reflects state BEFORE this pause was triggered)");

        // Clear all pending runnables on the main handler to prevent stale operations
        // (like delayed restarts) from occurring while paused.
        mainHandler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Cleared all pending Runnables from mainHandler in onPause.");

        cancelFinalResultTimeout(); // Always cancel watchdog if activity is paused

        // If we are NOT pausing because we initiated an action (like camera),
        // then we should stop recognition. If an action IS pending, stopRecognition()
        // should have already been called by handleCommand/takePhoto/recordVideo.
        if (!isActionPending) {
            Log.d(TAG, "onPause: No action pending, stopping recognition.");
            stopRecognition();
        } else {
            Log.d(TAG, "onPause: Action is pending (e.g., camera was launched). Recognizer should have been stopped by action method.");
            // It's still safe to call stopRecognition() again here as a safeguard,
            // as it handles being called multiple times.
            stopRecognition();
        }

        if (tts != null && tts.isSpeaking()) {
            tts.stop();
            Log.d(TAG, "TTS stopped in onPause because it was speaking.");
        }
        // DO NOT reset isActionPending here. onResume uses its value at the START of onPause
        // to determine if it's returning from an action we started.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy called. Releasing resources.");

        cancelFinalResultTimeout(); // Ensure watchdog is cleared
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null); // Clear any pending operations
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isTtsReady = false;
            Log.d(TAG, "TTS engine shut down.");
        }

        // Properly shutdown recognizer on a background thread if it exists
        if (recognizer != null) {
            final SpeechRecognizer localRecognizer = recognizer; // Use a local final variable for the thread
            recognizer = null; // Prevent further use from main thread immediately
            isRecognizerReady = false; // Mark as not ready
            isRecognizerInitializing = false;

            new Thread(() -> { // Shutdown on a background thread to avoid ANR if it's slow
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
