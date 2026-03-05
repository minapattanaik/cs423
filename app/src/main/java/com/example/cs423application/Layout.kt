package com.example.cs423application

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun MyApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { homescreen(navController) }
        composable("upload") { uploadscreen() }
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
//    Button(
//        onClick = {navController.navigate("upload") },
//        modifier = Modifier
//            .padding(start = 30.dp, top = 300.dp, end = 30.dp, bottom = 300.dp),    // Moves it up from the very edge
//
//    ) {
//        Text("Click Me")
//    }
}
@Composable
fun uploadscreen(){
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.back),
            contentDescription = null, // Set to null for decorative backgrounds
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop // Ensures the image fills the screen
        )
    }
}


@Preview
@Composable
fun homescreenPreview() {
    val navController = rememberNavController()
    homescreen(navController = navController)
}