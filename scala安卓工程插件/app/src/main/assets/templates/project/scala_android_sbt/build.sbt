import modernandroid.AndroidKeys.*

ThisBuild / organization := "${organization}"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "${scala_version}"

lazy val root = (project in file("."))
  .enablePlugins(modernandroid.AndroidApp)
  .settings(
    name := "${project_name}",
    platformTarget := "${platform_target}",
    androidApplicationId := "${package_name}",
    androidMinSdk := ${min_sdk},
    androidTargetSdk := ${target_sdk},
    androidVersionCode := 1,
    androidVersionName := "${version_name}",
    androidReleaseKeystore := Some(baseDirectory.value / "release-test.keystore"),
    androidReleaseKeystoreAlias := Some("release"),
    androidReleaseKeystorePassword := Some("changeit"),
    androidReleaseKeyPassword := Some("changeit"),
    Compile / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / publishArtifact := false
  )
