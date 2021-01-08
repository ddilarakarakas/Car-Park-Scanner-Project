package com.example.xpark.Activities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.xpark.DataBaseProvider.FirebaseDBConstants;
import com.example.xpark.Module.CarPark;
import com.example.xpark.Module.User;
import com.example.xpark.R;
import com.example.xpark.Utils.ToastMessageConstants;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.time.ZoneId;
import es.dmoral.toasty.Toasty;

public class ParkingInformationActivity extends AppCompatActivity implements OnMapReadyCallback {

    private Button finishPark_button;
    private TextView textTime;
    private User currentUser;
    private CarPark selectedpark;
    private Button qrScanButton;
    private Boolean qrBoolean;

    public static String park_id = "";
    public static Boolean isScanned = false;

    /* CAMERA PERMISSION REQUEST CODE */
    private static final int MY_CAMERA_REQUEST_CODE = 100;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parking_information);
        System.out.println("PARKING INF ACTIVITY");
        init_logged_user();
        init_selected_carpark();

        UI_init();
        checkParking();
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        if(googleMap != null) {
            if (selectedpark != null) {
                LatLng coordinates = new LatLng(selectedpark.getLatitude(), selectedpark.getLongitude());
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinates, 16));
                googleMap.addMarker(new MarkerOptions().position(coordinates).title(selectedpark.getName()));
            }
        }
    }

    private void init_selected_carpark(){
        Intent intent = getIntent();
        selectedpark = (CarPark) intent.getSerializableExtra("CARPARK");
        System.out.println("GETTED CARPARK NAME:" + selectedpark.getName());
    }

    private void checkParking() {
        if (currentUser != null) {
            if (currentUser.getCarparkid().equals(ParkingInformationActivity.park_id))
                finishPark();
            else if (isScanned)
                Toasty.warning(this.getApplicationContext(), ToastMessageConstants.TOAST_MSG_ERROR_WRONG_QR, Toast.LENGTH_SHORT).show();
        } else {
            throw new ExceptionInInitializerError("The User cannot be getted...");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void scanCarParkQR() {
        /* Eğer uygulamanın kamera izni yoksa bunu sorar */
        if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, MY_CAMERA_REQUEST_CODE);
        } else {
            Intent intent = new Intent(getApplicationContext(), ScanCodeActivity.class);
            intent.putExtra("CURRENT_USER", currentUser);
            startActivity(intent);
            finish();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void UI_init() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map2);
        mapFragment.getMapAsync(this);

        finishPark_button = findViewById(R.id.button_finish);
        textTime = findViewById(R.id.text_time);
        qrScanButton = findViewById(R.id.QrScanner);
        textTime.setText(currentUser.getParkingTime());

        /* parkı bitir butonu */
        finishPark_button.setOnClickListener(v -> { finishPark(); });
        /* parkı tarayarak bitir butonu yukardaki şuanlık direkt parkı bitirir testler için kullanıyoruz */
        qrScanButton.setOnClickListener(v -> { scanCarParkQR(); });
    }

    private void init_logged_user() {
        Intent intent = getIntent();
        currentUser = (User) intent.getSerializableExtra("CURRENT_USER");
        System.out.println("USER GETTED : " + currentUser);
    }

    private void finishPark() {
        // if not parked yet, return
        if(currentUser.getCarparkid().equals(User.NOT_PARKED))
            return;

        LocalDateTime finishtime = LocalDateTime.now(ZoneId.of("Europe/Istanbul"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime parkingtime = LocalDateTime.parse(currentUser.getParkingTime(), formatter);

        calculateTime(finishtime, parkingtime);

        // Todo : handle balance and etc..
        this.removeUserFromCarpark();
    }

    private void calculateTime(LocalDateTime d1, LocalDateTime d2){
        long diff = ChronoUnit.MINUTES.between(d2, d1);

        System.out.println("CALCULATED TIME(minute): " + diff);
    }

    private void removeUserFromCarpark()
    {
        /* parse user */
        String carParkGnrlId = currentUser.getCarparkid();

        /* get district */
        String[] tokens = carParkGnrlId.split("-");
        String db_district_field = tokens[0];
        String db_carpark_id = tokens[1];
        System.out.println("DB DISTRICT = " + db_district_field);
        System.out.println("DB ID = " + db_carpark_id);

        /* find database reference from user */
        DatabaseReference pref = FirebaseDatabase.getInstance().getReference().child(FirebaseDBConstants.DB_CARPARK_FIELD).child(db_district_field).child(db_carpark_id);
        pref.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                /* get car park object from database */
                HashMap map = (HashMap)currentData.getValue();
                if(map == null)
                    return Transaction.success(currentData);

                /* get park object from data base */
                CarPark park = new CarPark((HashMap) currentData.getValue());

                /* increment free are in car park */
                park.decrementUsed();

                /* update the database */
                currentData.setValue(park);

                /* find user field in DB */
                DatabaseReference uref = FirebaseDatabase.getInstance().getReference().child(FirebaseDBConstants.DB_USER_FIELD).child(currentUser.getUid());
                currentUser.removeCarparkid();
                currentUser.removeParkingTime();

                /* update the user in DB */
                uref.setValue(currentUser);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot currentData) {
                System.out.println("Commit check : " + committed + " " + currentData.getValue());
                if(committed){
                    Intent intent = new Intent(getApplicationContext(), MapsActivity.class);
                    intent.putExtra("CURRENT_USER",currentUser);
                    startActivity(intent);
                    finish();
                }
                //this.runOnUiThread(() -> Toasty.warning(this.getApplicationContext(), ToastMessageConstants.TOAST_MSG_INFO_MAP_UPDATED, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
