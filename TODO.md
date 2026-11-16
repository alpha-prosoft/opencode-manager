- some credentiasl ara mandatory (can be defined by tenantn admin or team admin) so user has to fill them in 
- make tennant visbile in URL and  project first class citizen in UI so user can navigate (like i.e project/repository thingy in github/bitbucket). Add global search by name. it wil then then search so user can search quick Ctrl+K as well. In results we shot matching projects, matching teams and matchin session (session for this user)


- fork dialog: only offer teams that may run the session's harness. Store with each harness what it requires (models and LLM connections, MCP servers, credentials, image), and when a person picks the team a fork lands in, list only the teams granted all of it and say why the others are missing. Until then the picker lists all of the person's teams and the fork is refused at start if the team lacks something.
