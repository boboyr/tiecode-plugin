package ${package_name}

import android.app.Activity
import android.os.Bundle
import android.widget.{Button, TextView, Toast}

class MainActivity extends Activity:
  private var taps = 0

  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    val helloText = findViewById(R.id.helloText).asInstanceOf[TextView]
    val actionButton = findViewById(R.id.actionButton).asInstanceOf[Button]

    helloText.setText("Hello from Scala 3 + sbt 2!")
    actionButton.setOnClickListener(_ =>
      taps += 1
      val msg = s"Scala 3 on Android works. taps=$taps"
      helloText.setText(msg)
      Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    )
