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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = null, // Set to null for decorative backgrounds
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop // Ensures the image fills the screen
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 5.dp, top = 75.dp, end = 5.dp, bottom = 5.dp),

        contentAlignment = Alignment.TopCenter
    ) {
        Image(
            painter = painterResource(id = R.drawable.imgxlogo),
            contentDescription = stringResource(id = R.string.logo_contentdescription)
        )

    }

    Image(
        painter = painterResource(id = R.drawable.tagline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 140.dp, end = 40.dp, bottom = 40.dp),
        contentScale = ContentScale.Crop,
        contentDescription = stringResource(id = R.string.taglinedescription)

    )

    Image(
        painter = painterResource(id = R.drawable.polaroid),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 300.dp, end = 30.dp, bottom = 30.dp),
        contentScale = ContentScale.Crop,
        contentDescription = stringResource(id = R.string.taglinedescription)

    )
    OutlinedButton(
            onClick = { navController.navigate("upload") },
    border = BorderStroke(1.dp, Color(0xFF000000)),
    colors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color(0xFFBCC9EB),
        contentColor = Color(0xFF000000)
    ),
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = 50.dp, top = 750.dp, end = 50.dp)
    ) {
        Text("GET STARTED", fontSize = 25.sp)
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