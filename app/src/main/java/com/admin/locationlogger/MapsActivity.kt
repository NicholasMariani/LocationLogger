package com.admin.locationlogger

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.database.CursorIndexOutOfBoundsException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import kotlin.math.abs

class MapsActivity : FragmentActivity(), OnMapReadyCallback {
    private var context: Context? = null
    private var mMap: GoogleMap? = null
    private var mLocationRequest: LocationRequest? = null
    private var mFusedLocationClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private var provider: String? = null
    private var db: SQLiteDatabase? = null
    private var storedLocation: Location? = null
    private var currLocation: Location? = null
    private var currLatLng: LatLng? = null
    private var storedLatLng: LatLng? = null
    private var storedDescription: String? = null
    private var searching = false
    private var storedMarkers: LinkedList<MarkerOptions>? = null
    private var searchMarker: MarkerOptions? = null
    private var cancel: Button? = null
    private var distance: TextView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        provider = LocationManager.NETWORK_PROVIDER
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment!!.getMapAsync(this)

//        this.deleteDatabase("location_logger");
        db = openOrCreateDatabase("location_logger", MODE_PRIVATE, null)
        db!!.execSQL("CREATE TABLE IF NOT EXISTS Locations(description VARCHAR UNIQUE, latitude INT, longitude INT, found BOOLEAN);")
        context = this
        searching = false
        distance = findViewById(R.id.distance)
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add storedMarkers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        storedMarkers = LinkedList()
        searchMarker = null
        mMap!!.mapType = GoogleMap.MAP_TYPE_NORMAL
        setupGoogleMapScreenSettings(googleMap)
        initWidgets()
        mLocationRequest = LocationRequest()
        mLocationRequest!!.smallestDisplacement = 1f //update every meter
        mLocationRequest!!.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager!!.requestLocationUpdates(provider!!, 5000, 0f, locationListener)
                mMap!!.isMyLocationEnabled = true
            } else {
                checkLocationPermission()
            }
        } else {
            locationManager!!.requestLocationUpdates(provider!!, 5000, 0f, locationListener)
            mMap!!.isMyLocationEnabled = true
        }
        setUpMapClicks()
        dropStoredPins()
    }

    private fun setUpMapClicks() {
        mMap!!.setOnMapLongClickListener { latLng: LatLng ->
            var found = false
            println("STORED " + storedMarkers!!.size)
            for (marker in storedMarkers!!) {
                if (abs(marker.position.latitude - latLng.latitude) < 0.00003 && abs(
                        marker.position.longitude - latLng.longitude
                    ) < 0.00003
                ) {
                    found = true
                    storedDescription = marker.title
                    var action =
                        "Resume Search for (" + marker.position.latitude + ", " + marker.position.longitude + ")"
                    if (searchMarker != null) {
                        action = "Replace entry: " + marker.title
                    }
                    AlertDialog.Builder(context)
                        .setTitle("Choose an Option")
                        .setMessage("Choose an action to perform on this marker:")
                        .setPositiveButton(action) { dialogInterface: DialogInterface, _: Int ->
                            val contentValues = ContentValues()
                            contentValues.put("found", "0")
                            db!!.update(
                                "Locations",
                                contentValues,
                                "description = ?",
                                arrayOf(marker.title)
                            )
                            if (searchMarker != null) {
                                db!!.delete(
                                    "Locations", "description = ?", arrayOf(
                                        searchMarker!!.title
                                    )
                                )
                            }
                            mMap!!.clear()
                            dropStoredPins()
                            dialogInterface.cancel()
                        }
                        .setNeutralButton("Cancel") { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                        .setNegativeButton("Remove entry: " + marker.title) { dialogInterface: DialogInterface, _: Int ->
                            db!!.delete("Locations", "description = ?", arrayOf(marker.title))
                            mMap!!.clear()
                            dropStoredPins()
                            dialogInterface.cancel()
                        }
                        .create()
                        .show()
                }
                break
            }
            if (!found && searchMarker != null) {
                if (abs(searchMarker!!.position.latitude - latLng.latitude) < 0.00003 && abs(
                        searchMarker!!.position.longitude - latLng.longitude
                    ) < 0.00003
                ) {
                    storedDescription = searchMarker!!.title
                    AlertDialog.Builder(context)
                        .setTitle("Choose an Option")
                        .setMessage("Choose an action to perform on this marker:")
                        .setPositiveButton("End Search for (" + searchMarker!!.position.latitude + ", " + searchMarker!!.position.longitude + ")") { dialogInterface: DialogInterface, _: Int ->
                            val contentValues = ContentValues()
                            contentValues.put("found", "1")
                            db!!.update(
                                "Locations", contentValues, "description = ?", arrayOf(
                                    searchMarker!!.title
                                )
                            )
                            mMap!!.clear()
                            searchMarker = null
                            dropStoredPins()
                            dialogInterface.cancel()
                        }
                        .setNeutralButton("Cancel") { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                        .setNegativeButton("Remove entry: " + searchMarker!!.title) { dialogInterface: DialogInterface, _: Int ->
                            db!!.delete(
                                "Locations", "description = ?", arrayOf(
                                    searchMarker!!.title
                                )
                            )
                            mMap!!.clear()
                            searchMarker = null
                            dropStoredPins()
                            dialogInterface.cancel()
                        }
                        .create()
                        .show()
                }
            }
        }
    }

    private val locationListener = LocationListener { location -> //Place current location
        currLocation = location
        currLatLng = LatLng(location.latitude, location.longitude)
        println("Current coords: $currLatLng")
        //move map camera
        mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(currLatLng!!, 25f))
        if (storedLatLng != null && searching) {
            println("Distance: " + location.distanceTo(storedLocation!!))
            distance!!.text = location.distanceTo(storedLocation!!).toInt().toString() + "m"
            if (abs(currLatLng!!.latitude - storedLatLng!!.latitude) < 0.00003
                && abs(currLatLng!!.longitude - storedLatLng!!.longitude) < 0.00003
            ) {
                AlertDialog.Builder(context)
                    .setTitle("Logged Location Nearby")
                    .setMessage("The coordinates you are searching for are very close to your current location.")
                    .setPositiveButton("End Search") { _: DialogInterface?, _: Int ->
                        val contentValues = ContentValues()
                        contentValues.put("found", "1")
                        db!!.update(
                            "Locations",
                            contentValues,
                            "description = ?",
                            arrayOf(storedDescription)
                        )
                        searching = false
                        cancel!!.visibility = View.INVISIBLE
                        distance!!.visibility = View.INVISIBLE
                        mMap!!.clear()
                        searchMarker = null
                        dropStoredPins()
                    }
                    .setNegativeButton("Still Looking!") { _: DialogInterface?, _: Int ->
                        searching = true
                    }
                    .create()
                    .show()
            }
        }
    }

    private fun setupGoogleMapScreenSettings(mMap: GoogleMap) {
        mMap.isBuildingsEnabled = true
        mMap.isIndoorEnabled = true
        mMap.isTrafficEnabled = true
        mMap.uiSettings.isMapToolbarEnabled = false
        val mUiSettings = mMap.uiSettings
        mUiSettings.isMyLocationButtonEnabled = false
        mUiSettings.isScrollGesturesEnabled = true
        mUiSettings.isZoomGesturesEnabled = true
        mUiSettings.isTiltGesturesEnabled = true
        mUiSettings.isRotateGesturesEnabled = false
    }

    private fun initWidgets() {
        val layers = findViewById<ImageView>(R.id.layers)
        layers.setOnClickListener {
            when (mMap!!.mapType) {
                1 -> {
                    mMap!!.mapType = GoogleMap.MAP_TYPE_HYBRID
                    findViewById<View>(R.id.bar).setBackgroundColor(resources.getColor(R.color.lightOpaque))
                }

                4 -> {
                    mMap!!.mapType = GoogleMap.MAP_TYPE_NORMAL
                    findViewById<View>(R.id.bar).setBackgroundColor(resources.getColor(R.color.opaque))
                }

                else -> {}
            }
        }
        val markers = findViewById<ImageView>(R.id.markers)
        markers.setOnClickListener {
            val resultSet = db!!.rawQuery("Select * from Locations order by description desc", null)
            val values: MutableMap<Int, Array<String?>> = HashMap()
            val query = arrayOfNulls<String>(resultSet.count)
            resultSet.use { resultSet ->
                var i = 0
                while (resultSet.moveToNext()) {
                    val tmp = arrayOfNulls<String>(3)
                    tmp[0] = resultSet.getString(0)
                    val r1 = tmp[0]
                    val r2 = resultSet.getDouble(1)
                    val r3 = resultSet.getDouble(2)
                    tmp[1] = r2.toString()
                    tmp[2] = r3.toString()
                    values[i] = tmp
                    query[i] = "$r1:\n$r2, $r3"
                    println("r1 = $r1")
                    println("r2 = $r2")
                    println("r3 = $r3")
                    i++
                }
            }
            val ad = AlertDialog.Builder(context)
            ad.setTitle("Choose coordinates")
            ad.setItems(query) { _: DialogInterface?, i: Int ->
                val selection = LatLng(
                    java.lang.Double.valueOf(
                        values[i]!![1]!!
                    ), java.lang.Double.valueOf(values[i]!![2]!!)
                )
                mMap!!.animateCamera(CameraUpdateFactory.newLatLngZoom(selection, 25f))
            }
            ad.create()
            ad.show()
        }
        val store = findViewById<FloatingActionButton>(R.id.floatingActionButton)
        store.setOnClickListener {
            if (searchMarker != null) {
                println("Search marker: $searchMarker")
                AlertDialog.Builder(context)
                    .setTitle("Search Already In Progress")
                    .setMessage("The previous search coordinates were never found. What action would you like to perform?")
                    .setPositiveButton("End Search for (" + searchMarker!!.position.latitude + ", " + searchMarker!!.position.longitude + ")") { dialogInterface: DialogInterface, _: Int ->
                        val contentValues = ContentValues()
                        contentValues.put("found", "1")
                        db!!.update(
                            "Locations", contentValues, "description = ?", arrayOf(
                                searchMarker!!.title
                            )
                        )
                        mMap!!.clear()
                        insertIntoDb()
                        dialogInterface.cancel()
                    }
                    .setNeutralButton("Cancel") { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                    .setNegativeButton("Replace entry: " + searchMarker!!.title) { dialogInterface: DialogInterface, _: Int ->
                        db!!.delete(
                            "Locations", "description = ?", arrayOf(
                                searchMarker!!.title
                            )
                        )
                        mMap!!.clear()
                        insertIntoDb()
                        dialogInterface.cancel()
                    }
                    .create()
                    .show()
            } else {
                insertIntoDb()
            }
        }
        cancel = findViewById(R.id.cancel)
        cancel!!.setOnClickListener {
            if (searchMarker != null) {
                println("Search marker: $searchMarker")
                AlertDialog.Builder(context)
                    .setTitle("Cancel Search")
                    .setMessage("Cancel search for " + searchMarker!!.position.latitude + ", " + searchMarker!!.position.longitude + "?")
                    .setPositiveButton("Cancel search") { dialogInterface: DialogInterface, _: Int ->
                        searching = false
                        dialogInterface.cancel()
                        cancel!!.visibility = View.INVISIBLE
                        distance!!.visibility = View.INVISIBLE
                    }
                    .setNeutralButton("Still looking") { dialogInterface: DialogInterface, _: Int ->
                        searching = true
                        dialogInterface.cancel()
                    }
                    .create()
                    .show()
            }
        }
        val begin = findViewById<Button>(R.id.begin)
        begin.setOnClickListener {
            if (searchMarker != null) {
                println("Search marker: $searchMarker")
                AlertDialog.Builder(context)
                    .setTitle("Begin Search")
                    .setMessage("Beginning search for " + searchMarker!!.position.latitude + ", " + searchMarker!!.position.longitude)
                    .setPositiveButton("Let's go!") { dialogInterface: DialogInterface, _: Int ->
                        searching = true
                        dialogInterface.cancel()
                        cancel!!.visibility = View.VISIBLE
                        distance!!.visibility = View.VISIBLE
                    }
                    .setNeutralButton("Cancel") { dialogInterface: DialogInterface, _: Int ->
                        searching = false
                        dialogInterface.cancel()
                    }
                    .create()
                    .show()
            }
        }
    }

    private fun dropStoredPins() {
        try {
            mMap!!.clear()
            selectFromDb("0")
            selectFromDb("1")
        } catch (e: CursorIndexOutOfBoundsException) {
            e.printStackTrace()
        }
    }

    private fun insertIntoDb() {
        try {
            val dt = SimpleDateFormat("MM-dd-yy HH:mm:ss").format(Date())
            if (ActivityCompat.checkSelfPermission(
                    context!!, Manifest.permission.ACCESS_FINE_LOCATION
                )
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(
                    context!!, Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val lat = currLatLng!!.latitude.toString()
            val lon = currLatLng!!.longitude.toString()
            db!!.execSQL("INSERT INTO Locations VALUES('$dt', $lat, $lon, 0);")
            Toast.makeText(this, "Current coordinates have been added!", Toast.LENGTH_LONG).show()
            dropStoredPins()
            storedDescription = dt
            storedLocation = currLocation
            storedLatLng = LatLng(lat.toDouble(), lon.toDouble())
        } catch (e: SQLiteConstraintException) {
            e.printStackTrace()
        }
    }

    private fun selectFromDb(value: String) {
        storedMarkers = LinkedList()
        val resultSet = db!!.rawQuery("Select * from Locations WHERE found = $value", null)
        resultSet.use { resultSet ->
            while (resultSet.moveToNext()) {
                val r1 = resultSet.getString(0)
                val r2 = resultSet.getString(1)
                val r3 = resultSet.getString(2)
                println("CURRENT LAT: $r2")
                println("CURRENT LON: $r3")
                val mo = MarkerOptions()
                    .title(r1)
                    .position(LatLng(r2.toDouble(), r3.toDouble()))
                    .snippet("Lat: $r2, Lon: $r3")
                if (value == "0") {
                    searchMarker = MarkerOptions()
                    mo.icon(BitmapDescriptorFactory.fromResource(R.drawable.pin))
                    searchMarker = mo
                } else {
                    storedMarkers!!.add(mo)
                }
                mMap!!.addMarker(mo)
            }
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            ) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
                AlertDialog.Builder(this)
                    .setTitle("Location Permission Needed")
                    .setMessage("This app needs the Location permission, please accept to use location functionality")
                    .setPositiveButton("OK") { _: DialogInterface?, _: Int ->
                        //Prompt the user once explanation has been shown
                        ActivityCompat.requestPermissions(
                            this@MapsActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                            99
                        )
                    }
                    .create()
                    .show()
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    99
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 99) { // If request is cancelled, the result arrays are empty.
            if (grantResults.isNotEmpty()
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                // permission was granted, do the
                // location-related task you need to do.
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    locationManager!!.requestLocationUpdates(provider!!, 5000, 0f, locationListener)
                    mMap!!.isMyLocationEnabled = true
                }
            } else {
                // permission denied, disable the
                // functionality that depends on this permission.
                Toast.makeText(this, "permission denied", Toast.LENGTH_LONG).show()
            }
        }
    }
}