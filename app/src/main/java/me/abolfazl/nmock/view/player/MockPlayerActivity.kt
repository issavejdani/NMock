package me.abolfazl.nmock.view.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carto.core.ScreenPos
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.abolfazl.nmock.R
import me.abolfazl.nmock.databinding.ActivityMockPlayerBinding
import me.abolfazl.nmock.repository.models.MockDataClass
import me.abolfazl.nmock.utils.changeStringTo
import me.abolfazl.nmock.utils.isServiceStillRunning
import me.abolfazl.nmock.utils.managers.CameraManager
import me.abolfazl.nmock.utils.managers.LineManager
import me.abolfazl.nmock.utils.managers.MarkerManager
import me.abolfazl.nmock.utils.managers.UriManager
import me.abolfazl.nmock.utils.response.OneTimeEmitter
import me.abolfazl.nmock.utils.response.exceptions.EXCEPTION_DATABASE_GETTING_ERROR
import me.abolfazl.nmock.utils.response.exceptions.EXCEPTION_INSERTION_ERROR
import me.abolfazl.nmock.utils.showSnackBar
import me.abolfazl.nmock.utils.toPixel
import me.abolfazl.nmock.view.detail.MockDetailBottomSheetDialogFragment
import me.abolfazl.nmock.view.dialog.NMockDialog
import me.abolfazl.nmock.view.speedDialog.MockSpeedBottomSheetDialogFragment
import org.neshan.common.model.LatLng
import org.neshan.mapsdk.model.Marker
import org.neshan.mapsdk.model.Polyline

@AndroidEntryPoint
class MockPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMockPlayerBinding
    private val viewModel: MockPlayerViewModel by viewModels()

    //Layers
    private val markerLayer = ArrayList<Marker>()
    private val polylineLayer = ArrayList<Polyline>()

    private var serviceIsRunning = false
    private var mockPlayerService: MockPlayerService? = null

    companion object {
        const val KEY_MOCK_ID_PLAYER = "MOCK_PLAYER_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMockPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!isServiceStillRunning(MockPlayerService::class.java)) {
            startService(Intent(this, MockPlayerService::class.java))
        }
        initViewsFromBundle()

        initObservers()

        initListeners()
    }

    private fun initViewsFromBundle() {
        val mockId = intent.getLongExtra(KEY_MOCK_ID_PLAYER, -1)
        if (mockId == -1L) {
            this.finish()
            return
        }
        showProgressbar(true)
        viewModel.getMockInformation(mockId)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName?, binder: IBinder?) {
            serviceIsRunning = true
            val mockPlayerBinder = binder as MockPlayerService.MockPlayerBinder
            mockPlayerService?.setMockSpeed(viewModel.mockPlayerState.value.mockInformation?.speed!!)
            mockPlayerService?.setLineVectorForProcessing(viewModel.mockPlayerState.value.mockInformation?.lineVector!![0])
            mockPlayerService = mockPlayerBinder.getService()
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            serviceIsRunning = false
        }
    }

    override fun onStop() {
        super.onStop()
        if (!serviceIsRunning) return
        if (mockPlayerService?.mockIsRunning()!!) {
            unbindService(serviceConnection)
        } else {
            mockPlayerService?.stopIdleService()
        }
        serviceIsRunning = false
    }

    private fun initObservers() {
        lifecycleScope.launch {
            viewModel.mockPlayerState.collect { state ->
                state.mockInformation?.let { processMockInformation(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.oneTimeEmitter.collect { processAction(it) }
            }
        }
    }

    private fun processMockInformation(mockInformation: MockDataClass) {
        Intent(this@MockPlayerActivity, MockPlayerService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        showProgressbar(false)
        binding.titleTextView.text = mockInformation.mockName
        binding.originTextView.text =
            mockInformation.originAddress.changeStringTo("From:")
        binding.destinationTextView.text =
            mockInformation.destinationAddress.changeStringTo("To:")
        LineManager.drawLineOnMap(
            mapView = binding.mapview,
            polylineLayer = polylineLayer,
            vector = mockInformation.lineVector!!
        )
        val originMarker = MarkerManager.createMarker(
            location = mockInformation.originLocation,
            drawableRes = R.drawable.ic_origin_marker,
            elementId = MarkerManager.ELEMENT_ID_ORIGIN_MARKER,
            context = this@MockPlayerActivity
        )
        val destinationMarker = MarkerManager.createMarker(
            location = mockInformation.destinationLocation,
            drawableRes = R.drawable.ic_destination_marker,
            elementId = MarkerManager.ELEMENT_ID_DESTINATION_MARKER,
            context = this@MockPlayerActivity
        )
        if (originMarker != null && destinationMarker != null) {
            markerLayer.add(originMarker)
            markerLayer.add(destinationMarker)
            binding.mapview.addMarker(originMarker)
            binding.mapview.addMarker(destinationMarker)
        }
        LineManager.drawLineOnMap(
            mapView = binding.mapview,
            polylineLayer = polylineLayer,
            vector = mockInformation.lineVector
        )
        CameraManager.moveCameraToTripLine(
            mapView = binding.mapview,
            screenPos = ScreenPos(
                binding.root.x + 32.toPixel(this@MockPlayerActivity),
                binding.root.y + 32.toPixel(this@MockPlayerActivity)
            ),
            origin = mockInformation.originLocation,
            destination = mockInformation.destinationLocation
        )
    }

    private fun processAction(response: OneTimeEmitter<String>) {
        val message = when (response.exception) {
            EXCEPTION_INSERTION_ERROR -> getString(R.string.databaseInsertionException)
            EXCEPTION_DATABASE_GETTING_ERROR -> getString(R.string.databaseGettingException)
            else -> getString(R.string.unknownException)
        }

        showSnackBar(
            message = message,
            rootView = findViewById(R.id.mockPlayerRootView),
            Snackbar.LENGTH_SHORT
        )
    }

    private fun initializeMockListener() = lifecycleScope.launch {
        mockPlayerService?.startCreatingMockLocations()!!.collect { location ->
            var currentLocationMarker = MarkerManager.getMarkerFromLayer(
                layer = markerLayer,
                id = MarkerManager.ELEMENT_ID_CURRENT_LOCATION_MARKER
            )
            val latLng = LatLng(location.latitude, location.longitude)
            if (currentLocationMarker == null) {
                currentLocationMarker = MarkerManager.createMarker(
                    location = latLng,
                    drawableRes = R.drawable.current_location_marker,
                    context = this@MockPlayerActivity,
                    elementId = MarkerManager.ELEMENT_ID_CURRENT_LOCATION_MARKER,
                    markerSize = MarkerManager.CURRENT_LOCATION_MARKER_SIZE
                )
                currentLocationMarker?.let {
                    markerLayer.add(it)
                    binding.mapview.addMarker(currentLocationMarker)
                }
            } else {
                currentLocationMarker.latLng = latLng
            }
        }
    }

    private fun initListeners() {
        binding.backImageView.setOnClickListener { onBackClicked() }

        binding.detailImageView.setOnClickListener { onDetailClicked() }

        binding.playPauseFloatingActionButton.setOnClickListener { onPlayPauseClicked() }

        binding.stopFloatingActionButton.setOnClickListener { onStopClicked() }

        binding.speedFloatingActionButton.setOnClickListener { onSpeedFabClicked() }

        binding.shareMaterialButton.setOnClickListener { onShareClicked() }

        binding.navigateMaterialButton.setOnClickListener { onNavigationClicked() }
    }

    private fun onBackClicked() {
        if (!mockPlayerService?.mockIsRunning()!!) {
            this.finish()
            return
        }
        showEndDialog()
    }

    private fun onDetailClicked() {
        val detailDialog = MockDetailBottomSheetDialogFragment.newInstance(
            title = viewModel.mockPlayerState.value.mockInformation?.mockName!!,
            description = viewModel.mockPlayerState.value.mockInformation?.mockDescription!!,
            provider = viewModel.mockPlayerState.value.mockInformation?.provider!!,
            type = viewModel.mockPlayerState.value.mockInformation?.mockType!!,
            createdAt = viewModel.mockPlayerState.value.mockInformation?.createdAt!!,
            updatedAt = viewModel.mockPlayerState.value.mockInformation?.updatedAt!!
        )
        detailDialog.isCancelable = true
        detailDialog.show(supportFragmentManager.beginTransaction(), null)
    }

    private fun onPlayPauseClicked() {
        if (mockPlayerService?.mockIsRunning()!!) {
            mockPlayerService?.pauseOrPlayMock()
            binding.playPauseFloatingActionButton.setImageDrawable(getDrawable(R.drawable.ic_play_24))
        } else {
            binding.playPauseFloatingActionButton.setImageDrawable(getDrawable(R.drawable.ic_pause_24))
            if (mockPlayerService?.shouldReInitialize()!!) {
                mockPlayerService?.setLineVectorForProcessing(
                    viewModel.mockPlayerState.value.mockInformation?.lineVector!![0]
                )
                mockPlayerService?.setMockSpeed(
                    viewModel.mockPlayerState.value.mockInformation?.speed!!
                )
            }
            mockPlayerService?.initializeMockProvider()
            mockPlayerService?.pauseOrPlayMock()
            initializeMockListener()
        }
    }

    private fun onStopClicked() {
        if (!mockPlayerService?.mockIsRunning()!!) return
        mockPlayerService?.pauseOrPlayMock()
        mockPlayerService?.removeMockProvider()
        mockPlayerService?.resetResources()
        val currentLocationMarker = MarkerManager.getMarkerFromLayer(
            layer = markerLayer,
            id = MarkerManager.ELEMENT_ID_CURRENT_LOCATION_MARKER
        )
        currentLocationMarker?.let { marker ->
            markerLayer.remove(marker)
            binding.mapview.removeMarker(marker)
        }
        binding.playPauseFloatingActionButton.setImageDrawable(getDrawable(R.drawable.ic_play_24))
        showSnackBar(
            message = getString(R.string.mockPlayerServiceStoppedCompletely),
            rootView = findViewById(R.id.mockPlayerRootView),
            duration = Snackbar.LENGTH_LONG
        )
    }

    private fun onSpeedFabClicked() {
        val speedDialog = MockSpeedBottomSheetDialogFragment.newInstance(
            viewModel.mockPlayerState.value.mockInformation?.speed!!
        )
        speedDialog.isCancelable = false
        speedDialog.setOnSaveClickListener { newSpeed ->
            mockPlayerService?.setMockSpeed(newSpeed)
            viewModel.changeMockSpeed(newSpeed)
            speedDialog.dismiss()
        }
        speedDialog.show(supportFragmentManager.beginTransaction(), null)
    }

    private fun showProgressbar(show: Boolean) {
        binding.loadingProgressbar.visibility = if (show) View.VISIBLE else View.GONE
        binding.titleTextView.visibility = if (!show) View.VISIBLE else View.GONE
    }

    private fun onShareClicked() {
        val uri = UriManager.createShareUri(
            origin = viewModel.mockPlayerState.value.mockInformation?.originLocation!!,
            destination = viewModel.mockPlayerState.value.mockInformation?.destinationLocation!!,
            speed = viewModel.mockPlayerState.value.mockInformation?.speed!!
        )
        startActivity(
            Intent(Intent.ACTION_SEND, uri).apply {
                putExtra(Intent.EXTRA_TEXT, uri.toString())
                type = "text/plain"
            }
        )
    }

    private fun onNavigationClicked() {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                UriManager.createNavigationUri(viewModel.mockPlayerState.value.mockInformation?.destinationLocation!!)
            )
        )
    }

    private fun showEndDialog() {
        val dialog = NMockDialog.newInstance(
            title = getString(R.string.playerDialogTitle),
            actionButtonText = getString(R.string.stopMockService),
            secondaryButtonText = getString(R.string.justLeave)
        )
        dialog.isCancelable = true
        dialog.setDialogListener(
            onActionButtonClicked = {
                mockPlayerService?.stopIdleService()
                dialog.dismiss()
                this.finish()
            },
            onSecondaryButtonClicked = {
                dialog.dismiss()
                this.finish()
            }
        )
        dialog.show(supportFragmentManager.beginTransaction(), null)
    }

    override fun onBackPressed() {
        if (!mockPlayerService?.mockIsRunning()!!) {
            super.onBackPressed()
            return
        }
        showEndDialog()
    }

}