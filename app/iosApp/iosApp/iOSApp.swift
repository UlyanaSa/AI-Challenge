import SwiftUI
import shared

@main
struct iOSApp: App {
    let repository = ChatRepository(
        baseUrl: "http://localhost:8080"
    )

    var body: some Scene {
        WindowGroup {
            ContentView(repository: repository)
        }
    }
}

struct ContentView: View {
    let repository: ChatRepository
    @State private var messages: [ChatMessage] = []
    @State private var inputText: String = ""

    var body: some View {
        // Используем Compose UI через ComposeUIViewController
        ComposeView(repository: repository)
            .ignoresSafeArea()
    }
}