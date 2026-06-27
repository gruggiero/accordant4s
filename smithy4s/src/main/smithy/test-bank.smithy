$version: "2.0"

namespace accordant4s.testbank

/// A small bank service exercising smithy4s → accordant4s derivation.
/// The generated Scala `TestBankGen.Service` provides the `Service[Alg]`
/// that `SmithyOps.forService` introspects.
@trait(selector: "operation")
structure derivationNote {
    @required
    note: String
}

structure CreateAccountInput {
    @required
    accountId: String
}

structure CreateAccountOutput {
    @required
    accountId: String
    @required
    balance: Long
}

structure DepositInput {
    @required
    accountId: String
    @required
    amount: Long
}

structure DepositOutput {
    @required
    accountId: String
    @required
    newBalance: Long
}

structure WithdrawInput {
    @required
    accountId: String
    @required
    amount: Long
}

structure WithdrawOutput {
    @required
    accountId: String
    @required
    newBalance: Long
}

structure GetAccountInput {
    @required
    accountId: String
}

structure GetAccountOutput {
    @required
    accountId: String
    @required
    balance: Long
}

service TestBank {
    version: "1.0.0",
    operations: [CreateAccount, Deposit, Withdraw, GetAccount]
}

@derivationNote(note: "creates an account at balance 0")
operation CreateAccount {
    input: CreateAccountInput,
    output: CreateAccountOutput
}

@derivationNote(note: "deposits into an account")
operation Deposit {
    input: DepositInput,
    output: DepositOutput
}

@derivationNote(note: "withdraws from an account")
operation Withdraw {
    input: WithdrawInput,
    output: WithdrawOutput
}

@derivationNote(note: "gets an account's balance")
operation GetAccount {
    input: GetAccountInput,
    output: GetAccountOutput
}
