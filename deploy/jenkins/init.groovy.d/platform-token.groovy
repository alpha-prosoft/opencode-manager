import hudson.model.User
import jenkins.security.ApiTokenProperty

def username = System.getenv('JENKINS_PLATFORM_USER') ?: 'platform'
def token = System.getenv('JENKINS_PLATFORM_TOKEN')
if (token) {
  def user = User.getById(username, true)
  def property = user.getProperty(ApiTokenProperty.class)
  if (property == null) {
    property = new ApiTokenProperty()
    user.addProperty(property)
  }
  def store = property.tokenStore
  store.tokenListSortedByName.findAll { it.name == 'platform' }.each { store.revokeToken(it.uuid) }
  store.addFixedNewToken('platform', token, (java.time.LocalDate) null)
  user.save()
  println "platform-token: API token of ${username} set"
}
