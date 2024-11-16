package com.example.myapplication;

import com.example.myapplication.Pothole;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.IRegisterReceiver;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

public class MainActivity extends AppCompatActivity implements LocationListener {
    private static final String TAG = "MainActivity";
    private static final String BASE_URL = "http://47.129.31.47:3000"; // Localhost cho emulator
    private static final int PERMISSION_REQUEST_CODE = 1;

    private MapView map;
    private LocationManager locationManager;
    private MyLocationNewOverlay myLocationOverlay;

    private String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3MzE3NDQ1ODEsImV4cCI6MTczMTgzMDk4MSwiYXVkIjoiNjcyNWQzZTNmMGZiM2NmZDg2MDA4Y2Y3IiwiaXNzIjoiMjI1MjAxMjBAZ20udWl0LmVkdS52biJ9.l_P0sohNnVmTk2oaG_ttG3PYYxlMFsKe5VV6JsRLziw";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize osmdroid configuration
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        // Request permissions
        requestPermissions();

        // Initialize map
        map = findViewById(R.id.mapView);
        setupMap();
        setupLocation();

        // Setup location button
        FloatingActionButton fabLocation = findViewById(R.id.fab_my_location);
        fabLocation.setOnClickListener(v -> centerMapOnMyLocation());
    }

    private void setupMap() {
        // Định nghĩa bounds cho ĐHQG-HCM
        final double MIN_LAT = 10.8593387269177;
        final double MIN_LON = 106.734790771361;
        final double MAX_LAT = 10.89728831078;
        final double MAX_LON = 106.8587615275;

        // Setup custom tile source với bounds checking
        XYTileSource tileSource = new XYTileSource(
                "CustomMBTiles",
                8, 18, // min/max zoom
                256, // tile size
                ".png",
                new String[]{ BASE_URL + "/map/tiles/" }
        ) {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                int zoom = MapTileIndex.getZoom(pMapTileIndex);
                int x = MapTileIndex.getX(pMapTileIndex);
                int y = MapTileIndex.getY(pMapTileIndex);
                int tmsY = (int) (Math.pow(2, zoom) - 1 - y);
                // Kiểm tra xem tile có nằm trong khu vực không
                if (isTileInBounds(x, y, zoom)) {
                    String url = BASE_URL + "/map/tiles/" + zoom + "/" + x + "/" + tmsY + ".png";
                    Log.d(TAG, "Requesting tile in bounds: " + url);
                    return url + "?Authorization=Bearer " + token;
                } else {
                    Log.d(TAG, "Tile out of bounds: z=" + zoom + " x=" + x + " y=" + y);
                    return null;
                }
            }
        };

        Configuration.getInstance().getAdditionalHttpRequestProperties().put(
                "Authorization",
                "Bearer " + token
        );

        // Setup map view
        map.setTileSource(tileSource);
        map.setMultiTouchControls(true);
        map.setHorizontalMapRepetitionEnabled(false);
        map.setVerticalMapRepetitionEnabled(false);

        // Set bounds giới hạn
        BoundingBox bounds = new BoundingBox(
                MAX_LAT, MAX_LON,  // North, East
                MIN_LAT, MIN_LON   // South, West
        );
        map.setScrollableAreaLimitDouble(bounds);

        // Set zoom và center
        map.getController().setZoom(15.0);
        GeoPoint center = new GeoPoint(
                (MIN_LAT + MAX_LAT) / 2,
                (MIN_LON + MAX_LON) / 2
        );
        map.getController().setCenter(center);

        loadPotholes();
    }

    // Helper method để kiểm tra tile có nằm trong bounds không
    private boolean isTileInBounds(int x, int y, int zoom) {
        // Convert tile coordinates to lat/lon
        double north = tile2lat(y, zoom);
        double south = tile2lat(y + 1, zoom);
        double west = tile2lon(x, zoom);
        double east = tile2lon(x + 1, zoom);

        // Check overlap với bounds của ĐHQG
        return !(east < 106.734790771361 || // west bound
                west > 106.8587615275 ||    // east bound
                south > 10.89728831078 ||   // north bound
                north < 10.8593387269177);  // south bound
    }

    // Convert tile coordinates to latitude
    private static double tile2lat(int y, int z) {
        double n = Math.PI - (2.0 * Math.PI * y) / Math.pow(2.0, z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    // Convert tile coordinates to longitude
    private static double tile2lon(int x, int z) {
        return x / Math.pow(2.0, z) * 360.0 - 180;
    }

    private void setupLocation() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        myLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
        myLocationOverlay.enableMyLocation();
        map.getOverlays().add(myLocationOverlay);

        if (checkLocationPermission()) {
            startLocationUpdates();
        }
    }

    private boolean checkLocationPermission() {
        return ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdates() {
        if (checkLocationPermission()) {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    10,
                    this
            );
        }
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{permission},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void centerMapOnMyLocation() {
        if (checkLocationPermission()) {
            Location lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (lastKnownLocation != null) {
                GeoPoint myPosition = new GeoPoint(
                        lastKnownLocation.getLatitude(),
                        lastKnownLocation.getLongitude()
                );
                map.getController().animateTo(myPosition);
            } else {
                Toast.makeText(this, "Đang tìm vị trí của bạn...", Toast.LENGTH_SHORT).show();
            }
        } else {
            requestPermissions();
        }
    }

    private void loadPotholes() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(BASE_URL + "/map/getAllPothole")
                .addHeader("Authorization", "Bearer " + token)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Error loading potholes", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    try {
                        Gson gson = new Gson();
                        Type type = new TypeToken<List<Pothole>>(){}.getType();
                        List<Pothole> potholes = gson.fromJson(json, type);

                        runOnUiThread(() -> displayPotholes(potholes));
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing pothole data", e);
                    }
                }
            }
        });
    }

    private void displayPotholes(List<Pothole> potholes) {
        // Clear existing markers
        map.getOverlays().removeIf(overlay -> overlay instanceof Marker);

        // Add markers for each pothole
        for (Pothole pothole : potholes) {
            Marker marker = new Marker(map);
            marker.setPosition(new GeoPoint(
                    pothole.getLocation().getCoordinates().getLatitude(),
                    pothole.getLocation().getCoordinates().getLongitude()
            ));

            // Customize marker appearance based on severity
            Drawable icon;
            switch (pothole.getSeverity().getLevel()) {
                case "High":
                    icon = ContextCompat.getDrawable(this, R.drawable.marker_red);
                    break;
                case "Medium":
                    icon = ContextCompat.getDrawable(this, R.drawable.marker_red);
                    break;
                default:
                    icon = ContextCompat.getDrawable(this, R.drawable.marker_red);
                    break;
            }
            marker.setIcon(icon);

            // Add info window
            marker.setTitle("Pothole");
            marker.setSnippet(String.format(
                    "Severity: %s\nDimension: %s\nDepth: %s",
                    pothole.getSeverity().getLevel(),
                    pothole.getDescription().getDimension(),
                    pothole.getDescription().getDepth()
            ));

            map.getOverlays().add(marker);
        }

        // Refresh map
        map.invalidate();
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        GeoPoint myPosition = new GeoPoint(location.getLatitude(), location.getLongitude());
        map.getController().animateTo(myPosition);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        map.onResume();
        if (checkLocationPermission()) {
            startLocationUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        map.onPause();
        locationManager.removeUpdates(this);
    }
}