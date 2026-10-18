package ${package_name}

import android.graphics.drawable.Drawable
import android.widget.Toast

import com.rapid.api.framework.domain.menu.BaseDomainMenuAction
import com.rapid.framework.data.DataHolder

class TestMenuAction extends BaseDomainMenuAction {

  override def getId: String = "test1"

  override def getTitle: String = "测试1"

  override def getIcon: Drawable = null

  override def perform(dataHolder: DataHolder): Unit = {
    Toast.makeText(getActivity, "测试", Toast.LENGTH_SHORT).show()
  }
}
