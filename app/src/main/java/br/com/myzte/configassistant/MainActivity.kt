package br.com.myzte.configassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var ssidInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else status("Permissão da câmera negada.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        ssidInput = findViewById(R.id.ssidInput)
        passwordInput = findViewById(R.id.passwordInput)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<Button>(R.id.captureButton).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { requestWifiConnection() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                status("Câmera pronta. Enquadre a etiqueta e fotografe.")
            } catch (e: Exception) {
                status("Não foi possível iniciar a câmera: ${e.localizedMessage}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            status("A câmera ainda não está pronta.")
            return
        }

        val photoFile = File(cacheDir, "onu-label-${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    recognizeLabel(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    status("Erro ao fotografar: ${exception.localizedMessage}")
                }
            }
        )
        status("Fotografando etiqueta...")
    }

    private fun recognizeLabel(file: File) {
        status("Lendo texto da etiqueta...")
        try {
            val image = InputImage.fromFilePath(this, android.net.Uri.fromFile(file))
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    val credentials = LabelParser.parse(fullText)
                    ssidInput.setText(credentials.ssid.orEmpty())
                    passwordInput.setText(credentials.password.orEmpty())

                    status(
                        if (credentials.ssid != null || credentials.password != null)
                            "Leitura concluída. Confira os dados antes de conectar."
                        else
                            "Não encontrei SSID e senha com segurança. Você pode preenchê-los manualmente."
                    )
                }
                .addOnFailureListener { error ->
                    status("Falha no OCR: ${error.localizedMessage}")
                }
        } catch (e: Exception) {
            status("Não foi possível processar a foto: ${e.localizedMessage}")
        }
    }

    private fun requestWifiConnection() {
        val ssid = ssidInput.text?.toString()?.trim().orEmpty()
        val password = passwordInput.text?.toString().orEmpty()

        if (ssid.isBlank()) {
            status("Informe o nome da rede (SSID).")
            return
        }
        if (password.length < 8) {
            status("Confira a senha. Para WPA/WPA2, normalmente são necessários pelo menos 8 caracteres.")
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status("A conexão assistida por este método exige Android 10 ou superior.")
            return
        }

        try {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            status("Solicitando conexão. Aceite a solicitação do Android.")
            connectivityManager.requestNetwork(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        connectivityManager.bindProcessToNetwork(network)
                        runOnUiThread { status("Conectado à rede $ssid. A rede pode não ter internet.") }
                    }

                    override fun onUnavailable() {
                        runOnUiThread { status("O Android não conseguiu conectar. Confira a senha e tente novamente.") }
                    }

                    override fun onLost(network: Network) {
                        connectivityManager.bindProcessToNetwork(null)
                        runOnUiThread { status("A conexão Wi-Fi foi encerrada.") }
                    }
                }
            )
        } catch (e: SecurityException) {
            status("Permissão necessária não concedida: ${e.localizedMessage}")
        } catch (e: Exception) {
            status("Não foi possível solicitar a conexão: ${e.localizedMessage}")
        }
    }

    private fun status(message: String) {
        statusText.text = message
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        recognizer.close()
    }
}
