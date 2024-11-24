package com.particlesdevs.photoncamera.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.elvishew.xlog.libcat.LibCat;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.particlesdevs.photoncamera.ui.camera.CameraActivity;
import com.particlesdevs.photoncamera.util.FileManager;
import com.particlesdevs.photoncamera.util.log.PFileNameGenerator;

import java.io.File;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final File path = new File(
                FileManager.sPHOTON_DIR, "PhotonLog");
        Printer filePrinter = new FilePrinter                      // Printer that print(save) the log to file
                .Builder(path.getPath())                         // Specify the directory path of log file(s)
                .fileNameGenerator(new PFileNameGenerator())        // Default: ChangelessFileNameGenerator("log")
                .backupStrategy(new NeverBackupStrategy())             // Default: FileSizeBackupStrategy(1024 * 1024)
                .cleanStrategy(new FileLastModifiedCleanStrategy(7*24*60*60*1000)) // Clean the log file(s) when the number of day is greater than 7
                .build();
        LibCat.config(true, filePrinter);
        startActivity(new Intent(SplashActivity.this, CameraActivity.class));
        finish();
    }
}
