package com.example.flashlight;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

/**
 * Main activity for the Flashlight app.
 * Handles permissions, torch control, UI state, and lifecycle.
 */
public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final long DEBOUNCE_DELAY_MS = 500; // ignore rapid taps

    // Views
    private FloatingActionButton fabToggle;
    private TextView tvStatus;

    // Torch control
    private CameraManager cameraManager;
    private String cameraId; // ID of the camera with flash
    private boolean isFlashAvailable = false;

    // State
    private boolean flashlightDesiredState = false; // whether user wants the light ON
    private boolean isTorchOn = false;             // actual hardware state (used for UI feedback)
    private boolean isToggling = false;            // debounce flag

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fabToggle = findViewById(R.id.fab_toggle);
        tvStatus = findViewById(R.id.tv_status);

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        // Restore desired state from saved instance (e.g., after rotation)
        if (savedInstanceState != null) {
            flashlightDesiredState = savedInstanceState.getBoolean("flashlightDesiredState", false);
        }

        // Check flash availability and request permission if needed
        checkFlashAvailability();

        // Set click listener with debounce
        fabToggle.setOnClickListener(v -> {
            if (isToggling) return; // debounce
            if (!isFlashAvailable) {
                showToast(getString(R.string.no_flash_available));
                return;
            }
            // If permission not granted, request it (with rationale if needed)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestCameraPermission();
                return;
            }

            // Toggle the light
            toggleFlashlight();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Restore torch state if user had left it ON before pausing
        if (flashlightDesiredState && isFlashAvailable
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            setTorchMode(true);
        }
        updateUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Always release the torch when going to background
        if (isTorchOn) {
            setTorchMode(false);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("flashlightDesiredState", flashlightDesiredState);
    }

    /**
     * Checks whether the device has a camera flash unit.
     * If not, disables the button and shows a message.
     */
    private void checkFlashAvailability() {
        boolean hasFeature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        if (!hasFeature) {
            isFlashAvailable = false;
            disableFlashlight();
            showToast(getString(R.string.no_flash_available));
            return;
        }

        // Additional check via CameraManager: find a camera with FLASH_INFO available
        try {
            for (String id : cameraManager.getCameraIdList()) {
                if (cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) != null
                        && cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                    cameraId = id;
                    isFlashAvailable = true;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
            isFlashAvailable = false;
        }

        if (!isFlashAvailable) {
            disableFlashlight();
            showToast(getString(R.string.no_flash_available));
        }
    }

    /**
     * Disables the toggle button and updates the status text.
     */
    private void disableFlashlight() {
        fabToggle.setEnabled(false);
        tvStatus.setText(getString(R.string.no_flash_available));
    }

    /**
     * Requests the CAMERA permission, showing rationale if appropriate.
     */
    private void requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            // Show rationale in a Snackbar and then request
            Snackbar.make(
                    findViewById(R.id.root_layout),
                    R.string.permission_rationale,
                    Snackbar.LENGTH_INDEFINITE
            ).setAction(android.R.string.ok, v ->
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.CAMERA},
                            CAMERA_PERMISSION_REQUEST_CODE)
            ).show();
        } else {
            // First request or "Don't ask again" case (will be handled in callback)
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted – nothing extra to do (user can now toggle)
                // If desired state was true, torch will be restored in onResume
                if (flashlightDesiredState) {
                    setTorchMode(true);
                }
            } else {
                // Permission denied
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.CAMERA)) {
                    // "Never ask again" selected – show Snackbar with action to open settings
                    showPermissionDeniedPermanentlySnackbar();
                } else {
                    showToast(getString(R.string.camera_permission_required));
                }
                // Turn off flashlight since we cannot control it
                flashlightDesiredState = false;
                updateUI();
            }
        }
    }

    /**
     * Shows a Snackbar that lets the user navigate to app settings to grant permission manually.
     */
    private void showPermissionDeniedPermanentlySnackbar() {
        Snackbar.make(
                findViewById(R.id.root_layout),
                R.string.camera_permission_required,
                Snackbar.LENGTH_INDEFINITE
        ).setAction(R.string.open_settings, v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        }).show();
    }

    /**
     * Toggles the flashlight state, respecting debounce.
     */
    private void toggleFlashlight() {
        if (isToggling) return;
        isToggling = true;

        flashlightDesiredState = !flashlightDesiredState;
        boolean success = setTorchMode(flashlightDesiredState);

        if (!success) {
            // If turning on failed, revert desired state
            flashlightDesiredState = !flashlightDesiredState;
        }
        updateUI();

        // Debounce reset
        handler.postDelayed(() -> isToggling = false, DEBOUNCE_DELAY_MS);
    }

    /**
     * Turns the camera torch on or off.
     *
     * @param on true to turn on, false to turn off.
     * @return true if the operation succeeded, false otherwise.
     */
    private boolean setTorchMode(boolean on) {
        if (cameraId == null || !isFlashAvailable) return false;
        try {
            cameraManager.setTorchMode(cameraId, on);
            isTorchOn = on;
            return true;
        } catch (CameraAccessException e) {
            isTorchOn = false;
            handleTorchError(e);
            return false;
        }
    }

    /**
     * Maps CameraAccessException to user-friendly error messages.
     */
    private void handleTorchError(CameraAccessException e) {
        int reason = e.getReason();
        if (reason == CameraAccessException.CAMERA_IN_USE ||
                reason == CameraAccessException.MAX_CAMERAS_IN_USE) {
            showToast(getString(R.string.camera_in_use));
        } else if (reason == CameraAccessException.CAMERA_DISCONNECTED ||
                reason == CameraAccessException.CAMERA_ERROR) {
            showToast(getString(R.string.hardware_error));
        } else {
            showToast(getString(R.string.hardware_error));
        }
    }

    /**
     * Updates button appearance and status text according to current torch state.
     */
    private void updateUI() {
        if (isTorchOn) {
            fabToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.flash_on));
            fabToggle.setImageResource(R.drawable.ic_flash_on);
            fabToggle.setContentDescription(getString(R.string.turn_off));
            tvStatus.setText("Flashlight is ON");
        } else {
            fabToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.flash_off));
            fabToggle.setImageResource(R.drawable.ic_flash_off);
            fabToggle.setContentDescription(getString(R.string.turn_on));
            tvStatus.setText("Flashlight is OFF");
        }
    }

    /**
     * Convenience method to show a short Toast.
     */
    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}