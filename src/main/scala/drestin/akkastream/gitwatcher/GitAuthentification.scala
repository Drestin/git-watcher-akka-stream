package drestin.akkastream.gitwatcher

import java.io.File

import com.jcraft.jsch.{JSch, Session}
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport._
import org.eclipse.jgit.util.FS

sealed trait GitAuthentification extends TransportConfigCallback {
}

case object NoAuthentification extends GitAuthentification {
  override def configure(transport: Transport): Unit = { /* do nothing */ }
}

final case class UsernamePasswordAuthentification(username: String, password: String) extends GitAuthentification {
  override def configure(transport: Transport): Unit = {
    transport.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
  }
}

final case class SshAuthentification(privateKeyFile: File, passphrase: Option[String]) extends GitAuthentification {
  private val factory = new JschConfigSessionFactory {
    override def configure(hc: OpenSshConfig.Host, session: Session): Unit = { /* do nothing */ }

    override def createDefaultJSch(fs: FS): JSch = {
      val jsch = super.createDefaultJSch(fs)
      if (passphrase.isEmpty) {
        jsch.addIdentity(privateKeyFile.getAbsolutePath)
      } else {
        jsch.addIdentity(privateKeyFile.getAbsolutePath, passphrase.get)
      }
      jsch
    }
  }

  override def configure(transport: Transport): Unit = {
    transport.asInstanceOf[SshTransport].setSshSessionFactory(factory)
  }
}