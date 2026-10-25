class UserController {
  constructor(private readonly userService: UserService) {}

  find(id: string): User {
    return this.userService.find(id);
  }
}

class FactoryHolder {
  build(): UserFactory {
    return new UserFactory();
  }
}
