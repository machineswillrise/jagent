///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//COMPILE_OPTIONS -parameters
//DEPS com.google.code.gson:gson:2.14.0
//DEPS dev.langchain4j:langchain4j:1.14.1
//DEPS dev.langchain4j:langchain4j-mistral-ai:1.14.1
//DEPS info.picocli:picocli:4.7.7
//DEPS io.jstach.rainbowgum:rainbowgum:0.8.2
//DEPS net.java.dev.jna:jna:5.18.1
//DEPS org.seleniumhq.selenium:selenium-java:4.43.0
//DEPS org.slf4j:slf4j-api:2.0.17

package org.jagent.jagent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import java.nio.charset.StandardCharsets;

import java.net.URLEncoder;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;

import java.time.Duration;

import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModelName;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;

import org.openqa.selenium.By;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.WebDriver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/*
 * jagent is a free ai coding tool
 * to use it, you need a free mistral api key (you have to agree to training)
 * you can get one if you go to the admin dashboard and verify with a phone number
 */

@Command(name = "jagent", mixinStandardHelpOptions = true, version = "1.0")
class Options {
	@Option(names = {"-d", "--dangerous"}, description = "Disable permission checks for tool calls.")
	public boolean DISABLE_PERMISSION_CHECKS = false;

	@Option(names = {"-a", "--api-key"}, description = "The Mistral API key to use.")
	public String MISTRAL_API_KEY;

	@Option(names = {"-i", "--init"}, description = "Initialize a fresh configuration file.")
	public transient boolean CREATE_CONFIG_FILE;

	@Option(names = {"-p", "--prompt"}, description = "The prompt to use for the AI.", arity = "1")
	public transient String PROMPT;

	@Option(names = {"-f", "--files"}, description = "List of files to work on.", arity = "1..*")
	public transient File[] FILES;
}

abstract class ToolSet {
	protected final boolean disablePermissionChecks;
	protected final Scanner scanner;

	public ToolSet(boolean disablePermissionChecks, Scanner scanner) {
		this.disablePermissionChecks = disablePermissionChecks;
		this.scanner = scanner;
	}

	protected boolean confirmAction(String toolName, String description) {
		if (disablePermissionChecks) {
			return true;
		}

		System.out.printf("[CONFIRM] %s: %s (y/n)? ", toolName, description);
		var response = scanner.nextLine().trim().toLowerCase();
		return response.equals("y") || response.equals("yes");
	}

	@Tool("Get the name of the current tool set that the agent can use.")
	public abstract String getToolSetName();
}

class FileBrowsingTools extends ToolSet {
	private static final Logger LOG = LoggerFactory.getLogger(FileBrowsingTools.class);

	public FileBrowsingTools(boolean disablePermissionChecks, Scanner scanner) {
		super(disablePermissionChecks, scanner);
	}

	@Tool("Get the current working directory.")
	public Path getCwd() {
		LOG.info("Getting current working directory");
		return Path.of(System.getProperty("user.dir"));
	}

	@Tool("List the files in a directory.")
	public List<Path> listFiles(String dir) throws IOException {
		LOG.info("Listing files in {}", dir);
		return Files.list(Path.of(dir))
			.collect(Collectors.toList());
	}

	private List<Path> walkFiles(String dir, List<Path> ignore) throws IOException {
		LOG.info("Walking files in {}, ignoring {}", dir, ignore);
		var result = new ArrayList<Path>();

		Files.walkFileTree(Path.of(dir), new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (ignore.stream().anyMatch(dir::endsWith)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				result.add(file);
				return FileVisitResult.CONTINUE;
			}
		});

		return result;
	}

	@Tool("Walk through the files in a directory, ignoring secrets, commit history, and build output.")
	public List<Path> walkFilesNoSecrets(String dir) throws IOException {
		LOG.info("Walking files in {}, ignoring secrets", dir);
		return walkFiles(dir, Arrays.asList(
			Path.of(".env"),
			Path.of(".git"),
			Path.of("node_modules"),
			Path.of("dist"),
			Path.of("build"),
			Path.of("target"), // maven or cargo
			Path.of("bin"),
			Path.of(".go-build")
		));
	}

	@Tool("Read an entire file.")
	public String readEntireFile(String file) throws IOException {
		LOG.info("Reading file {}", file);
		return Files.readString(Path.of(file));
	}

	@Tool("Get the size of a file.")
	public long getFileSize(String file) throws IOException {
		LOG.info("Getting size of {}", file);
		return Files.size(Path.of(file));
	}

	@Tool("Read a file with a specified offset and limit.")
	public String readPartialFile(String file, int offset, int limit) throws IOException {
		LOG.info("Reading partial file {} with offset {} and limit {}", file, offset, limit);
		try (var raf = new RandomAccessFile(Path.of(file).toFile(), "r")) {
			raf.seek(offset);
			var buf = new byte[limit];

			var bytesRead = raf.read(buf);
			if (bytesRead > 0) {
				return new String(buf, 0, bytesRead, StandardCharsets.UTF_8);
			}

			return "";
		}
	}

	@Tool("Write an entire file.")
	public void writeEntireFile(String file, String content) throws IOException {
		if (!confirmAction("writeEntireFile", "Write file: " + file)) {
			return;
		}

		LOG.info("Writing entire file {}", file);
		Files.writeString(Path.of(file), content);
	}

	@Override
	public String getToolSetName() {
		return "File Browsing Tools";
	}
}

class ShellCommandTools extends ToolSet {
	private static final Logger LOG = LoggerFactory.getLogger(ShellCommandTools.class);

	public ShellCommandTools(boolean disablePermissionChecks, Scanner scanner) {
		super(disablePermissionChecks, scanner);
	}

	@Tool("Execute a shell command.")
	public String execShellCmd(String... cmd) throws IOException {
		if (!confirmAction("execShellCmd", "Execute command: " + String.join(" ", cmd))) {
			return null;
		}

		LOG.info("Running shell command {}", Arrays.toString(cmd));
		var process = new ProcessBuilder(cmd)
			.redirectErrorStream(true)
			.start();

		return new String(process.getInputStream().readAllBytes());
	}

	@Tool("Create one or more new directories.")
	public void createDirs(String... dirs) throws IOException {
		if (!confirmAction("createDirs", "Create directories: " + String.join(", ", dirs))) {
			return;
		}

		LOG.info("Creating directories {}", (Object) dirs);
		for (String dir : dirs) {
			Files.createDirectories(Path.of(dir));
		}
	}

	@Tool("Initialize a Git repository.")
	public void initRepo(String origin, boolean woke) throws IOException {
		if (!confirmAction("initRepo", "Initialize git repository with origin: " + origin)) {
			return;
		}

		LOG.info("Initializing git repository with origin {} and branch {}",
			origin, woke ? "main" : "master");
		execShellCmd("git", "init");
		execShellCmd("git", "remote", "add", "origin", origin);

		if (!woke) {
			return;
		}

		var defaultBranch = execShellCmd("git", "config", "--global", "init.defaultBranch");
		if (defaultBranch.equals("main")) {
			execShellCmd("git", "branch", "-m", "master", "main");
		}
	}

	@Tool("Get the last X Git commits.")
	public String getLastCommits(int x) throws IOException {
		LOG.info("Getting the last {} Git commits", x);
		return execShellCmd("git", "log", "-n", String.valueOf(x), "--oneline");
	}

	@Tool("Add, commit, and push changes to Git.")
	public void commit(String msg) throws IOException {
		if (!confirmAction("commit", "Commit and push changes: " + msg)) {
			return;
		}

		LOG.info("Publishing changes");
		execShellCmd("git", "add", ".");
		execShellCmd("git", "commit", "-m", msg);
		execShellCmd("git", "push");
	}

	@Override
	public String getToolSetName() {
		return "Shell Command Tools";
	}
}

class WebBrowsingTools extends ToolSet {
	private final WebDriver driver;
	private static final Logger LOG = LoggerFactory.getLogger(WebBrowsingTools.class);

	// the scanner is not actually used here because nothing is dangerous
	public WebBrowsingTools(WebDriver driver, boolean disablePermissionChecks, Scanner scanner) {
		super(disablePermissionChecks, scanner);
		this.driver = driver;
	}

	@Tool("Get the contents of a given webpage.")
	public String getPageContent(String url) {
		LOG.info("Getting contents of {}", url);
		driver.get(url);
		return driver.getPageSource();
	}

	@Tool("Get the search results for a given query.")
	public Map<String, String> getSearchResults(String query) {
		LOG.info("Getting search results for {}", query);
		var encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
		var url = String.format("https://duckduckgo.com/?q=%s", encodedQuery);

		driver.get(url);
		var wait = new WebDriverWait(driver, Duration.ofSeconds(10));
		var results = wait.until(ExpectedConditions.visibilityOfAllElementsLocatedBy(By.cssSelector(".result__a")));
		var found = new HashMap<String, String>();

		for (var result : results) {
			var title = result.getText();
			var website = result.getAttribute("href");
			found.put(title, website);
		}

		return found;
	}

	public void quit() {
		LOG.info("Browser shutting down...");
		driver.quit();
	}

	@Override
	public String getToolSetName() {
		return "Web Browsing Tools";
	}
}

record LibreOfficeDocument(String content, String styles, String meta, String settings, String manifest) {}

class LibreOfficeTools extends ToolSet {
	public LibreOfficeTools(boolean disablePermissionChecks, Scanner scanner) {
		super(disablePermissionChecks, scanner);
	}

	@Tool("""
		Generate a LibreOffice document at the specified path.
		Content, styles, meta, settings, and manifest are XML strings that define the document structure.
		""")
	public void generateDocument(LibreOfficeDocument document, String path) throws IOException {
		if (!confirmAction("generateDocument", "Generate LibreOffice document at " + path)) {
			return;
		}

		var entries = List.of(
			Map.entry("content.xml", document.content()),
			Map.entry("styles.xml", document.styles()),
			Map.entry("meta.xml", document.meta()),
			Map.entry("settings.xml", document.settings()),
			Map.entry("META-INF/manifest.xml", document.manifest())
		);

		try (var zipOut = new ZipOutputStream(new FileOutputStream(path))) {
			for (var entry : entries) {
				var zipEntry = new ZipEntry(entry.getKey());
				zipOut.putNextEntry(zipEntry);
				zipOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zipOut.closeEntry();
			}
		}
	}

	@Override
	public String getToolSetName() {
		return "LibreOffice Tools";
	}
}

interface Assistant {
	@SystemMessage("""
		From now on, you're in the role of an advanced AI coding assistant called JAgent.
		If the user asks more about you, say that you're written in Java and run in the terminal.
		Explain your work in a few short sentences in a way that would make sense to a non-technical user.
	""")
	TokenStream chat(@MemoryId String chatId, @UserMessage String message);
}

// getting the terminal size through jline didn't seem to work properly
class JNAStuff {
	public static final long TIOCGWINSZ = 0x5413;

	public static class WinSize extends Structure {
		public short ws_row;
		public short ws_col;
		public short ws_xpixel;
		public short ws_ypixel;

		@Override
		protected List<String> getFieldOrder() {
			return Arrays.asList("ws_row", "ws_col", "ws_xpixel", "ws_ypixel");
		}
	}

	public interface CLibrary extends Library {
		CLibrary INSTANCE = Native.load("c", CLibrary.class);
		int ioctl(int fd, long request, WinSize winsize);
	}
}

class ScanningUtil implements AutoCloseable {
	private final Scanner scanner;

	public ScanningUtil(Scanner scanner) {
		this.scanner = scanner;
	}

	public String scan(String output) {
		System.out.print(output);
		return scanner.nextLine();
	}

	@Override
	public void close() {
		scanner.close();
	}
}

class JAgent {
	private static final Logger LOG = LoggerFactory.getLogger(JAgent.class);
	private static final String CLEAR_SCREEN = "\033[H\033[2J";

	private static Gson initGson() {
		return new GsonBuilder()
			.setPrettyPrinting()
			.create();
	}

	private static MistralAiStreamingChatModel initModel(String apiKey) {
		return MistralAiStreamingChatModel.builder()
			.apiKey(apiKey)
			.modelName(MistralAiChatModelName.MISTRAL_LARGE_LATEST)
			.build();
	}

	private static ChatMemoryProvider initMemory(int max) {
		return id -> MessageWindowChatMemory.builder()
			.id(id)
			.maxMessages(max)
			.build();
	}

	private static WebDriver initBrowser() {
		var options = new FirefoxOptions();
		options.addArguments("--headless");
		return new FirefoxDriver(options);
	}

	private static Assistant initAgent(StreamingChatModel model, ChatMemoryProvider memory,
		WebDriver browser, boolean disablePermissionChecks, Scanner scanner) {
		var fileTools = new FileBrowsingTools(disablePermissionChecks, scanner);
		var shellTools = new ShellCommandTools(disablePermissionChecks, scanner);
		var webTools = new WebBrowsingTools(browser, disablePermissionChecks, scanner);
		var libreOfficeTools = new LibreOfficeTools(disablePermissionChecks, scanner);

		return AiServices.builder(Assistant.class)
			.streamingChatModel(model)
			.chatMemoryProvider(memory)
			.tools(fileTools, shellTools, webTools, libreOfficeTools)
			.build();
	}

	private static void logAndExit(String msg, boolean graceful) {
		LOG.info(msg);
		if (graceful) {
			System.exit(0);
		} else {
			System.exit(1);
		}
	}

	private static Options parseOptions(String[] args) {
		var options = new Options();
		var cmd = new CommandLine(options);

		try {
			cmd.parseArgs(args);

			if (cmd.isUsageHelpRequested()) {
				cmd.usage(System.out);
				System.exit(0);
			}

			return options;
		} catch (CommandLine.ParameterException e) {
			System.err.println(e.getMessage());
			cmd.usage(System.err);
			System.exit(1);
			return null; // unreachable
		}
	}

	private static Path getConfigPath() {
		var home = System.getProperty("user.home");
		var dir = Path.of(home, ".config", "jagent");
		return dir.resolve("config.json");
	}

	private static Options loadOrCreateConfig(Options options, Path config, Gson gson) {
		try {
			if (options.CREATE_CONFIG_FILE) {
				Files.createDirectories(config.getParent());

				if (Files.notExists(config)) {
					var json = gson.toJson(options);
					Files.writeString(config, json);
				}

				logAndExit("Created config file at " + config, true);
			}

			// load api key from config if not provided
			if (options.MISTRAL_API_KEY == null && Files.exists(config)) {
				var json = Files.readString(config);
				var configOptions = gson.fromJson(json, Options.class);
				options.MISTRAL_API_KEY = configOptions.MISTRAL_API_KEY;
			}
		} catch (IOException e) {
			logAndExit("Failed to handle config: " + e.getMessage(), false);
		}

		return options;
	}

	private static void validateApiKey(String apiKey) {
		if (apiKey == null) {
			logAndExit("Please add an API key (--api-key).", true);
		}
	}

	private static boolean isTooSmall() {
		var size = new JNAStuff.WinSize();
		var result = JNAStuff.CLibrary.INSTANCE.ioctl(1, JNAStuff.TIOCGWINSZ, size);
		if (result == 0 && (size.ws_row < 24 || size.ws_col < 80)) {
			return true;
		}
		return false;
	}

	private static String buildFileContext(File[] files) {
		if (files == null || files.length == 0) {
			return "";
		}

		var fileContext = new StringBuilder();
		fileContext.append("The following files have been provided for context:\n\n");
		for (File file : files) {
			try {
				var content = Files.readString(file.toPath());
				fileContext.append("--- File: ").append(file.getPath()).append(" ---\n");
				fileContext.append(content).append("\n\n");
			} catch (IOException e) {
				LOG.warn("Failed to read file {}: {}", file.getPath(), e.getMessage());
			}
		}
		return fileContext.toString();
	}

	// https://glaforge.dev/posts/2025/02/27/pretty-print-markdown-on-the-console
	private static String markdown(String md) {
		var replacements = new HashMap<String, String>() {{
			put("\\*\\*(.*?)\\*\\*",            "\u001B[1m$1\u001B[0m");
			put("\\*(.*?)\\*",                  "\u001B[3m$1\u001B[0m");
			put("__(.*?)__",                    "\u001B[4m$1\u001B[0m");
			put("~~(.*?)~~",                    "\u001B[9m$1\u001B[0m");
			put("(> ?.*)",                      "\u001B[3m\u001B[34m\u001B[1m$1\u001B[22m\u001B[0m");
			put("([\\d]+\\.|-|\\*) (.*)",       "\u001B[35m\u001B[1m$1\u001B[22m\u001B[0m $2");
			put("(?s)```(\\w+)?\\n(.*?)\\n```", "\u001B[3m\u001B[1m$1\u001B[22m\u001B[0m\n\u001B[57;107m$2\u001B[0m\n");
			put("`(.*?)`",                      "\u001B[57;107m$1\u001B[0m");
			put("(#{1,6}) (.*?)\n",             "\u001B[36m\u001B[1m$1 $2\u001B[22m\u001B[0m\n");
			put("(.*?\n={2,}\n)",               "\u001B[36m\u001B[1m$1\u001B[22m\u001B[0m\n");
			put("(.*?\n-{2,}\n)",               "\u001B[36m\u001B[1m$1\u001B[22m\u001B[0m\n");
			put("!\\[(.*?)]\\((.*?)\\)",        "\u001B[34m$1\u001B[0m (\u001B[34m\u001B[4m$2\u001B[0m)");
			put("!?\\[(.*?)]\\((.*?)\\)",       "\u001B[34m$1\u001B[0m (\u001B[34m\u001B[4m$2\u001B[0m]");
		}};

		for (var entry : replacements.entrySet()) {
			md = md.replaceAll(entry.getKey(), entry.getValue());
		}
		return md;
	}

	private static void prettyPrint(String md) {
		System.out.println(markdown(md));
	}

	private static void streamResponse(TokenStream tokenStream, Runnable onComplete)
		throws InterruptedException {
		var futureResponse = new CompletableFuture<Void>();
		var fullResponse = new StringBuilder();

		tokenStream
			.onPartialResponse(partialResponse -> {
				System.out.print(partialResponse);
				fullResponse.append(partialResponse);
			})
			.onCompleteResponse(response -> {
				System.out.print(CLEAR_SCREEN);
				prettyPrint(fullResponse.toString());

				onComplete.run();
				futureResponse.complete(null);
			})
			.onError(error -> {
				System.err.println("Error: " + error.getMessage());
				futureResponse.completeExceptionally(error);
			})
			.start();

		try {
			futureResponse.get();
		} catch (ExecutionException e) {
			// error already handled in onError callback
		}
	}

	private static void handleOneShot(Assistant agent, String sessionId, String prompt,
		String fileContext, WebDriver browser) throws InterruptedException {
		var message = fileContext.isEmpty() ? prompt : fileContext + prompt;
		var tokenStream = agent.chat(sessionId, message);

		streamResponse(tokenStream,
			() -> {
				browser.quit();
				logAndExit("Exiting...", true);
			});
	}

	private static void displayBoxedMessage(String message) {
		var padding = "─".repeat(message.length() + 2);
		var top     = "┌"  + padding +  "┐" + "\n";
		var middle  = "│ " + message + " │" + "\n";
		var bottom  = "└"  + padding +  "┘";
		System.out.println(top + middle + bottom);
	}

	public static void main(String[] args) {
		var options = parseOptions(args);
		var configPath = getConfigPath();
		var gson = initGson();

		options = loadOrCreateConfig(options, configPath, gson);

		var scanner = new Scanner(System.in);

		try (var scanningUtil = new ScanningUtil(scanner)) {
			if (isTooSmall()) {
				displayBoxedMessage("Warning: Your terminal may be too small.");
			}

			var browser = initBrowser();

			var apiKey = options.MISTRAL_API_KEY;
			validateApiKey(apiKey);

			var model = initModel(apiKey);
			var memory = initMemory(200);
			var agent = initAgent(model, memory, browser, options.DISABLE_PERMISSION_CHECKS, scanner);

			var sessionId = UUID.randomUUID().toString();
			var fileContext = buildFileContext(options.FILES);

			// oneshot mode
			if (options.PROMPT != null) {
				handleOneShot(agent, sessionId, options.PROMPT, fileContext, browser);
			}

			// interactive mode
			displayBoxedMessage("Welcome to JAgent!");

			while (true) {
				var message = scanningUtil.scan("jagent > ");
				switch (message) {
					case "/exit" -> {
						browser.quit();
						logAndExit("Exiting...", true);
					}
					case "/clear" -> System.out.print(CLEAR_SCREEN);
					case "" -> System.out.println("Please enter a message.");
					default -> {
						var fullMessage = fileContext.isEmpty() ? message : fileContext + message;
						var tokenStream = agent.chat(sessionId, fullMessage);

						streamResponse(tokenStream, () -> {});
					}
				}
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
		}
	}
}
