package com.example.tanbeehwatch;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class MainActivity extends Activity implements SensorEventListener {

    private DatabaseReference databaseRef;
    private SensorManager sensorManager;
    private Sensor accelerometer, heartRateSensor;
    private boolean waitingForReset = false;

    private static final float HIGH_ACCELERATION_THRESHOLD = 25f;
    private static final float STABLE_LOWER_BOUND = 9.0f;
    private static final float STABLE_UPPER_BOUND = 10.0f;
    private static final long STABLE_TIME_REQUIRED = 10000; // 10 ثواني
    private static final long RESET_FALL_TIME = 15000; // 15 ثانية

    private TextView heartRateText, statusText;

    private boolean highAccelerationDetected = false;
    private long highAccelerationTime = 0;
    private long stableStartTime = 0;
    private boolean fallDetected = false;

    private Handler handler = new Handler();
    private Runnable resetFallRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        heartRateText = findViewById(R.id.heartRateText);
        statusText = findViewById(R.id.statusText);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        }

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (heartRateSensor != null) {
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        databaseRef = FirebaseDatabase.getInstance().getReference("Users/qasim/vitals");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sensorType = event.sensor.getType();

        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            float acceleration = (float) Math.sqrt(x * x + y * y + z * z);
            long currentTime = System.currentTimeMillis();

            // تسارع عالي = احتمال سقوط
            if (acceleration > HIGH_ACCELERATION_THRESHOLD && !fallDetected && !waitingForReset) {
                highAccelerationDetected = true;
                highAccelerationTime = currentTime;
                stableStartTime = 0;
                Log.d("FallCheck", "تسارع عالي تم رصده");
            }

            // نراقب الثبات فقط إذا كنا في وضع متابعة السقوط
            if (highAccelerationDetected && !fallDetected && !waitingForReset) {
                if (acceleration >= STABLE_LOWER_BOUND && acceleration <= STABLE_UPPER_BOUND) {
                    if (stableStartTime == 0) {
                        stableStartTime = currentTime;
                    } else if (currentTime - stableStartTime >= STABLE_TIME_REQUIRED) {
                        fallDetected = true;
                        waitingForReset = true;

                        statusText.setText("🚨 تم اكتشاف سقوط!");
                        Log.d("FallDetector", "تم تأكيد السقوط!");
                        databaseRef.child("fallDetected").setValue(true);

                        if (resetFallRunnable != null) handler.removeCallbacks(resetFallRunnable);
                        resetFallRunnable = () -> {
                            fallDetected = false;
                            waitingForReset = false;
                            highAccelerationDetected = false;
                            stableStartTime = 0;

                            statusText.setText("الحالة طبيعية");
                            databaseRef.child("fallDetected").setValue(false);
                            Log.d("FallDetector", "تم إلغاء السقوط، الشخص تحرك");
                        };
                    }
                } else {
                    stableStartTime = 0;
                }
            }

            // بدأ الشخص يتحرك بعد السقوط؟ نبدأ العد التنازلي لإلغاءه
            if (fallDetected && acceleration > STABLE_UPPER_BOUND && resetFallRunnable != null) {
                handler.postDelayed(resetFallRunnable, RESET_FALL_TIME);
            }

            // حفظ التسارع
            databaseRef.child("Accelerometer").setValue((double) Math.round(acceleration * 100) / 100);
        }

        if (sensorType == Sensor.TYPE_HEART_RATE) {
            float heartRate = event.values[0];
            heartRateText.setText("نبضات القلب: " + heartRate + " bpm");
            Log.d("HeartRateMonitor", "نبضات القلب: " + heartRate);
            databaseRef.child("heartRate").setValue(heartRate);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // لا شيء
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
    }
}
