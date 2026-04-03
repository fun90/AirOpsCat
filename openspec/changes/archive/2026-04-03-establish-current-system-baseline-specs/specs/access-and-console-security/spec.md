## ADDED Requirements

### Requirement: Form-based authentication SHALL protect the console and authenticated APIs
The system SHALL expose a public login page and use form-based authentication to protect all routes except explicitly configured public and health endpoints.

#### Scenario: Public login entry is available
- **WHEN** an unauthenticated user requests `/login`
- **THEN** the system returns the login page without requiring prior authentication

#### Scenario: Authenticated console access is required
- **WHEN** an unauthenticated user requests `/dashboard` or `/console/{module}/{page}`
- **THEN** the system requires authentication before granting access

### Requirement: Role-based access policies SHALL gate protected route groups
The system SHALL enforce role-specific route groups so that admin routes require `ADMIN`, partner routes require `ADMIN` or `PARTNER`, and VIP routes require `ADMIN`, `PARTNER`, or `VIP`.

#### Scenario: Partner cannot access admin-only API
- **WHEN** an authenticated user without the `ADMIN` role requests `/api/admin/*`
- **THEN** the system denies access

#### Scenario: VIP route accepts higher roles
- **WHEN** an authenticated user with role `VIP`, `PARTNER`, or `ADMIN` requests `/api/vip/*`
- **THEN** the system allows the request

### Requirement: Console pages SHALL be served through module-based navigation
The system SHALL provide a shared console entry model with a dashboard root and module page rendering for authenticated users.

#### Scenario: Dashboard is rendered for signed-in users
- **WHEN** an authenticated user requests `/dashboard`
- **THEN** the system returns the dashboard page

#### Scenario: Module console page is resolved dynamically
- **WHEN** an authenticated user requests `/console/{module}/{page}`
- **THEN** the system resolves and renders the requested console module page

### Requirement: Authentication state endpoints SHALL expose current-user and logout behavior
The system SHALL provide authenticated user information and logout endpoints for signed-in sessions.

#### Scenario: Current user information is available after login
- **WHEN** an authenticated session requests `/api/user`
- **THEN** the system returns information about the current user

#### Scenario: Logout ends the authenticated session
- **WHEN** an authenticated session requests `/api/logout`
- **THEN** the system invalidates the session and the user must authenticate again to access protected routes
