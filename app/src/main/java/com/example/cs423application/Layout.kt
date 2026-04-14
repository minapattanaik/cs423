package com.example.cs423application

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun MyApp() {
    val navController = rememberNavController()
    val vm: ImageViewModel = viewModel()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { homescreen(navController) }
        composable("pipeline") { ImagePipelineScreen(vm) }
        composable("upload") { uploadscreen(navController, vm) }
    }
}
@Composable
fun homescreen(navController: NavController) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val screenWidth  = LocalConfiguration.current.screenWidthDp.dp
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = null, // Set to null for decorative backgrounds
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop // Ensures the image fills the screen
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = screenWidth * 0.07f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter            = painterResource(id = R.drawable.imgxlogo),
            modifier           = Modifier.fillMaxWidth(0.6f),
            contentDescription = stringResource(id = R.string.logo_contentdescription)
        )

        Image(
            painter            = painterResource(id = R.drawable.tagline),
            modifier           = Modifier.fillMaxWidth(0.6f),
            contentScale       = ContentScale.Fit,
            contentDescription = stringResource(id = R.string.taglinedescription)
        )

        Image(
            painter            = painterResource(id = R.drawable.polaroid),
            modifier           = Modifier
                .fillMaxWidth(1.0f)
                .height(screenHeight * 0.50f)
                .offset(y = (-20).dp),
            contentScale       = ContentScale.Fit,
            contentDescription = stringResource(id = R.string.polaroiddescription)
        )
        Spacer(modifier = Modifier.height(screenHeight * 0.01f))

        OutlinedButton(
            onClick  = { navController.navigate("upload") },
            border   = BorderStroke(1.dp, Color(0xFF000000)),
            colors   = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0xFFBCC9EB),
                contentColor   = Color(0xFF000000)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = screenWidth * 0.12f)
        ) {
            Text("GET STARTED", fontSize = 25.sp)
        }
    }

}
@Composable
fun uploadscreen(navController: NavController, vm: ImageViewModel){
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = null, // Set to null for decorative backgrounds
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop // Ensures the image fills the screen
        )
    }
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            vm.processImage(it)
            navController.navigate("pipeline")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color.Transparent)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),

        ) {
        Spacer(modifier = Modifier.height(50.dp))
        Text(
            text = "UPLOAD YOUR IMAGE",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            )
        )
        Image(
            painter = painterResource(id = R.drawable.upload),
            contentDescription = "Upload image",
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 100.dp, end = 20.dp, bottom = 20.dp)
                .clickable { pickerLauncher.launch("image/*") },
            contentScale = ContentScale.Crop
        )
    }
}

@Preview
@Composable
fun homescreenPreview() {
    val navController = rememberNavController()
    homescreen(navController = navController)
}