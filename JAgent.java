///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.google.code.gson:gson:2.14.0
//DEPS dev.langchain4j:langchain4j:1.22.0
//DEPS dev.langchain4j:langchain4j-mistral-ai:1.22.0
//DEPS dev.langchain4j:langchain4j-open-ai:1.22.0
//DEPS info.picocli:picocli:4.7.7
//DEPS io.jstach.rainbowgum:rainbowgum:0.15.1
//DEPS org.jline:jline:4.4.8
//DEPS org.seleniumhq.selenium:selenium-java:4.51.0
//DEPS org.slf4j:slf4j-api:2.0.20

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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.time.Duration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.mistralai.MistralAiChatModelName;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.UserMessage;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import org.openqa.selenium.By;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/*
 * JAgent is a free AI coding agent written in Java.
 * It can either use the Mistral API or use the OpenCode API for free by sending the
 * 'Authorization: Bearer public' header. The free Mistral AI is smarter but can do
 * rate-limiting more than OpenCode so you'll have a better experience with a paid version.
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

	// this avoids duplicating the response twice when scraping stdout 
	@Option(names = {"-r", "--raw-markdown"}, description = "Output raw Markdown without formatting.")
	public transient boolean RAW_MARKDOWN = false;

	@Option(names = {"-f", "--files"}, description = "List of files to work on.", arity = "1..*")
	public transient File[] FILES;
}

abstract class ToolSet {
	protected final boolean disablePermissionChecks;
	protected final LineReader lineReader;

	public ToolSet(boolean disablePermissionChecks, LineReader lineReader) {
		this.disablePermissionChecks = disablePermissionChecks;
		this.lineReader = lineReader;
	}

	protected boolean confirmAction(String toolName, String description) {
		if (disablePermissionChecks) {
			return true;
		}

		String response = lineReader
			.readLine("[CONFIRM] %s: %s (y/n)? ".formatted(toolName, description))
			.trim()
			.toLowerCase();

		return response.equals("y") || response.equals("yes");
	}
}

class PermissionDeniedException extends RuntimeException {
	public PermissionDeniedException(String message) {
		super(message);
	}
}

class FileBrowsingTools extends ToolSet {
	private static final Logger LOG = LoggerFactory.getLogger(FileBrowsingTools.class);
	private static final List<String> SECRETS = List.of(
		".env",
		".git",
		"node_modules",
		"dist",
		"build",
		"target", // maven or cargo
		"bin",
		".go-build"
	);

	public FileBrowsingTools(boolean disablePermissionChecks, LineReader lineReader) {
		super(disablePermissionChecks, lineReader);
	}

	@Tool("Get the current working directory.")
	public Path getCwd() {
		LOG.info("Getting current working directory");
		return Path.of(System.getProperty("user.dir"));
	}

	private void checkPermission(String file) {
		if (SECRETS.contains(file)) {
			LOG.warn("Access to {} is not allowed", file);
			throw new PermissionDeniedException("Access to " + file + " is not allowed");
		}
	}

	@Tool("List the files in a directory.")
	public List<Path> listFiles(String dir) throws IOException {
		LOG.info("Listing files in {}", dir);
		checkPermission(dir);
		return Files.list(Path.of(dir))
			.collect(Collectors.toList());
	}

	private List<Path> walkFiles(String dir, List<String> ignore) throws IOException {
		LOG.info("Walking files in {}, ignoring {}", dir, ignore);
		List<Path> result = new ArrayList<>();

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
		checkPermission(dir);
		return walkFiles(dir, SECRETS);
	}


	@Tool("Get the size of a file.")
	public long getFileSize(String file) throws IOException {
		LOG.info("Getting size of {}", file);
		checkPermission(file);
		return Files.size(Path.of(file));
	}

	@Tool("Read an entire file.")
	public String readEntireFile(String file) throws IOException {
		LOG.info("Reading file {}", file);
		checkPermission(file);
		return Files.readString(Path.of(file));
	}

	@Tool("Read a file with a specified offset and limit.")
	public String readPartialFile(String file, int offset, int limit) throws IOException {
		LOG.info("Reading partial file {} with offset {} and limit {}", file, offset, limit);
		checkPermission(file);
		try (RandomAccessFile raf = new RandomAccessFile(Path.of(file).toFile(), "r")) {
			raf.seek(offset);
			byte[] buf = new byte[limit];

			int bytesRead = raf.read(buf);
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
		checkPermission(file);
		Files.writeString(Path.of(file), content);
	}
}

class ShellCommandTools extends ToolSet {
	private static final Logger LOG = LoggerFactory.getLogger(ShellCommandTools.class);

	public ShellCommandTools(boolean disablePermissionChecks, LineReader lineReader) {
		super(disablePermissionChecks, lineReader);
	}

	@Tool("Execute a shell command.")
	public String execShellCmd(String... cmd) throws IOException {
		if (!confirmAction("execShellCmd", "Execute command: " + String.join(" ", cmd))) {
			return null;
		}

		LOG.info("Running shell command {}", Arrays.toString(cmd));
		Process process = new ProcessBuilder(cmd)
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
	public void initRepo(String origin, boolean renameToMain) throws IOException {
		if (!confirmAction("initRepo", "Initialize git repository with origin: " + origin)) {
			return;
		}

		LOG.info("Initializing git repository with origin {} and branch {}",
			origin, renameToMain ? "main" : "master");
		execShellCmd("git", "init");
		execShellCmd("git", "remote", "add", "origin", origin);

		if (!renameToMain) {
			return;
		}

		String defaultBranch = execShellCmd("git", "config", "--global", "init.defaultBranch");
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
}

class WebBrowsingTools extends ToolSet {
	private static final Logger LOG = LoggerFactory.getLogger(WebBrowsingTools.class);
	private final WebDriver driver;

	public WebBrowsingTools(boolean disablePermissionChecks, WebDriver driver, LineReader lineReader) {
		super(disablePermissionChecks, lineReader);
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
		String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
		String url = String.format("https://duckduckgo.com/?q=%s", encodedQuery);

		driver.get(url);
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
		List<WebElement> results = wait.until(ExpectedConditions.visibilityOfAllElementsLocatedBy(By.cssSelector(".result__a")));
		HashMap<String, String> found = new HashMap<String, String>();

		for (WebElement result : results) {
			String title = result.getText();
			String website = result.getAttribute("href");
			found.put(title, website);
		}

		return found;
	}

	public void quit() {
		LOG.info("Browser shutting down...");
		driver.quit();
	}
}

class MiscTools extends ToolSet {
	public MiscTools(boolean disablePermissionChecks, LineReader lineReader) {
		super(disablePermissionChecks, lineReader);
	}

	@Tool("Ask the user a question.")
	public String askQuestion(String question) {
		System.out.println(question);
		return lineReader.readLine("Enter your answer: ");
	}
}

record LibreOfficeDocument(
	String content,
	String styles,
	String meta,
	String settings,
	String manifest
) {}

class LibreOfficeTools extends ToolSet {
	public LibreOfficeTools(boolean disablePermissionChecks, LineReader lineReader) {
		super(disablePermissionChecks, lineReader);
	}

	@Tool(
		"""
		Generate a LibreOffice document at the specified path.
		Content, styles, meta, settings, and manifest are XML strings that define the document structure.
		"""
	)
	public void generateDocument(LibreOfficeDocument document, String path) throws IOException {
		if (!confirmAction("generateDocument", "Generate LibreOffice document at " + path)) {
			return;
		}

		List<Map.Entry<String, String>> entries = List.of(
			Map.entry("content.xml", document.content()),
			Map.entry("styles.xml", document.styles()),
			Map.entry("meta.xml", document.meta()),
			Map.entry("settings.xml", document.settings()),
			Map.entry("META-INF/manifest.xml", document.manifest())
		);

		try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(path))) {
			for (Map.Entry<String, String> entry : entries) {
				ZipEntry zipEntry = new ZipEntry(entry.getKey());
				zipOut.putNextEntry(zipEntry);
				zipOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zipOut.closeEntry();
			}
		}
	}
}

interface Assistant {
	@SystemMessage(
		"""
		From now on, you're in the role of an advanced open-source AI coding assistant called JAgent that runs in the terminal.

		Explain your work in a few short sentences in a way that would make sense to a non-technical user.
		Keep most of your responses only a few sentences long and only use Markdown if you absolutely need to.
		If the user doesn't specify where the project is, assume it's in the current directory.
		After finishing something, test it to make sure it works.
		"""
	)
	TokenStream chat(@MemoryId String chatId, @UserMessage String message);
}

class JAgent {
	private static final Logger LOG = LoggerFactory.getLogger(JAgent.class);
	private static final String CLEAR_SCREEN = "\033[H\033[2J";

	private static Gson initGson() {
		return new GsonBuilder()
			.setPrettyPrinting()
			.create();
	}

	private static StreamingChatModel initModel(String mistralApiKey) {
		StreamingChatModel model;
		int timeout = 90; // generating libreoffice documents can take a long time

		if (mistralApiKey == null) {
			Map<String, String> opencodeBearer = Map.of("Authorization", "Bearer public");
			model = OpenAiStreamingChatModel.builder()
				.baseUrl("https://opencode.ai/zen/v1")
				.modelName("big-pickle") // this one is free
				.customHeaders(opencodeBearer)
				.timeout(Duration.ofSeconds(timeout))
				.build();
		} else {
			model = MistralAiStreamingChatModel.builder()
				.apiKey(mistralApiKey)
				.modelName(MistralAiChatModelName.MISTRAL_LARGE_LATEST)
				.timeout(Duration.ofSeconds(timeout))
				.build();
		}

		return model;
	}

	private static ChatMemoryProvider initMemory(int max) {
		return id -> MessageWindowChatMemory.builder()
			.id(id)
			.maxMessages(max)
			.build();
	}

	private static WebDriver initBrowser() {
		FirefoxOptions options = new FirefoxOptions();
		options.addArguments("--headless");
		return new FirefoxDriver(options);
	}

	private static Assistant initAgent(StreamingChatModel model, ChatMemoryProvider memory,
		WebDriver browser, boolean disablePermissionChecks, LineReader lineReader) {
		var fileTools        = new FileBrowsingTools(disablePermissionChecks, lineReader);
		var shellTools       = new ShellCommandTools(disablePermissionChecks, lineReader);
		var webTools         = new WebBrowsingTools(disablePermissionChecks, browser, lineReader);
		var miscTools        = new MiscTools(disablePermissionChecks, lineReader);
		var libreOfficeTools = new LibreOfficeTools(disablePermissionChecks, lineReader);

		return AiServices.builder(Assistant.class)
			.streamingChatModel(model)
			.chatMemoryProvider(memory)
			.tools(fileTools, shellTools, webTools, miscTools, libreOfficeTools)
			.toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
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
		Options options = new Options();
		CommandLine cmd = new CommandLine(options);

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
		String os = System.getProperty("os.name");
		Path dir;

		if (os.startsWith("Windows")) {
			dir = Path.of(System.getenv("APPDATA"), "AppData", "Roaming", "jagent");
		} else if (os.startsWith("Mac")) {
			dir = Path.of(System.getProperty("user.home"), "Library", "Application Support", "jagent");
		} else {
			dir = Path.of(System.getProperty("user.home"), ".config", "jagent");
		}

		return dir.resolve("config.json");
	}

	private static Options loadOrCreateConfig(Options options, Path config, Gson gson) {
		try {
			if (options.CREATE_CONFIG_FILE) {
				Files.createDirectories(config.getParent());

				if (Files.notExists(config)) {
					String json = gson.toJson(options);
					Files.writeString(config, json);
				}

				logAndExit("Created config file at " + config, true);
			}

			// load api key from config if not provided
			if (options.MISTRAL_API_KEY == null && Files.exists(config)) {
				String json = Files.readString(config);
				Options configOptions = gson.fromJson(json, Options.class);
				options.MISTRAL_API_KEY = configOptions.MISTRAL_API_KEY;
			}
		} catch (IOException e) {
			logAndExit("Failed to handle config: " + e.getMessage(), false);
		}

		return options;
	}

	private static String buildFileContext(File[] files) {
		if (files == null || files.length == 0) {
			return "";
		}

		StringBuilder fileContext = new StringBuilder();
		fileContext.append("The following files have been provided for context:\n\n");
		for (File file : files) {
			try {
				String content = Files.readString(file.toPath());
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
		Map<String, String> replacements = Map.ofEntries(
			Map.entry("\\*\\*(.*?)\\*\\*",            "\u001B[1m$1\u001B[0m"),
			Map.entry("\\*(.*?)\\*",                  "\u001B[3m$1\u001B[0m"),
			Map.entry("__(.*?)__",                    "\u001B[4m$1\u001B[0m"),
			Map.entry("~~(.*?)~~",                    "\u001B[9m$1\u001B[0m"),
			Map.entry("(> ?.*)",                      "\u001B[3m\u001B[34m\u001B[1m$1\u001B[22m\u001B[0m"),
			Map.entry("([\\d]+\\.|-|\\*) (.*)",       "\u001B[35m\u001B[1m$1\u001B[22m\u001B[0m $2"),
			Map.entry("(?s)```(\\w+)?\\n(.*?)\\n```", "\u001B[3m\u001B[1m$1\u001B[22m\u001B[0m\n\u001B[57;107m$2\u001B[0m\n"),
			Map.entry("`(.*?)`",                      "\u001B[57;107m$1\u001B[0m"),
			Map.entry("(#{1,6}) (.*?)\n",             "\u001B[36m\u001B[1m$1 $2\u001B[22m\u001B[0m\n"),
			Map.entry("(.*?\n={2,}\n)",               "\u001B[36m\u001B[1m$1\u001B[22m\u001B[0m\n"),
			Map.entry("(.*?\n-{2,}\n)",               "\u001B[36m\u001B[1m$1\u001B[22m\u001B[0m\n"),
			Map.entry("!\\[(.*?)]\\((.*?)\\)",        "\u001B[34m$1\u001B[0m (\u001B[34m\u001B[4m$2\u001B[0m)"),
			Map.entry("!?\\[(.*?)]\\((.*?)\\)",       "\u001B[34m$1\u001B[0m (\u001B[34m\u001B[4m$2\u001B[0m)")
		);

		for (Map.Entry<String, String> entry : replacements.entrySet()) {
			md = md.replaceAll(entry.getKey(), entry.getValue());
		}

		return md;
	}

	private static void prettyPrint(String md) {
		System.out.println(markdown(md));
	}

	private static void streamResponse(TokenStream tokenStream, Runnable onComplete, boolean rawMarkdown)
		throws InterruptedException {
		CompletableFuture<Void> futureResponse = new CompletableFuture<Void>();
		StringBuilder fullResponse = new StringBuilder();

		tokenStream
			.onPartialResponse(partialResponse -> {
				System.out.print(partialResponse);
				fullResponse.append(partialResponse);
			})
			.onCompleteResponse(response -> {
				if (!rawMarkdown) {
					System.out.print(CLEAR_SCREEN);
					prettyPrint(fullResponse.toString());
				}

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

	private static void handleOneShot(Assistant agent, String sessionId, boolean rawMarkdown,
		String prompt, String fileContext) throws InterruptedException {
		String message = fileContext.isEmpty() ? prompt : fileContext + prompt;
		TokenStream tokenStream = agent.chat(sessionId, message);

		streamResponse(tokenStream, () -> {}, rawMarkdown);
	}

	private static void displayBoxedMessage(String message) {
		var padding = "─".repeat(message.length() + 2);
		var top     = "┌"  + padding +  "┐" + "\n";
		var middle  = "│ " + message + " │" + "\n";
		var bottom  = "└"  + padding +  "┘";
		System.out.println(top + middle + bottom);
	}

	private static void runInteractiveLoop(LineReader lineReader,
		Assistant agent, String sessionId, String fileContext, boolean rawMarkdown) throws InterruptedException {
		while (true) {
			String message = lineReader.readLine("jagent > ");
			switch (message) {
				case "/exit" -> {
					return;
				}
				case "/clear" -> System.out.print(CLEAR_SCREEN);
				case "" -> System.out.println("Please enter a message.");
				default -> {
					String fullMessage = fileContext.isEmpty() ? message : fileContext + message;
					TokenStream tokenStream = agent.chat(sessionId, fullMessage);

					streamResponse(tokenStream, () -> {}, rawMarkdown);
				}
			}
		}
	}

	public static void main(String[] args) {
		var options = parseOptions(args);
		var gson = initGson();
		options = loadOrCreateConfig(options, getConfigPath(), gson);

		try (Terminal terminal = TerminalBuilder.builder().build()) {
			LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();

			WebDriver browser = initBrowser();
			StreamingChatModel model = initModel(options.MISTRAL_API_KEY);
			ChatMemoryProvider memory = initMemory(200);
			Assistant agent = initAgent(model, memory, browser, options.DISABLE_PERMISSION_CHECKS, lineReader);

			String sessionId = UUID.randomUUID().toString();
			String fileContext = buildFileContext(options.FILES);

			// oneshot mode
			if (options.PROMPT != null) {
				handleOneShot(agent, sessionId, options.RAW_MARKDOWN, options.PROMPT, fileContext);
			} else {
				// interactive mode
				displayBoxedMessage("Welcome to JAgent!");
				runInteractiveLoop(lineReader, agent, sessionId, fileContext, options.RAW_MARKDOWN);
			}

			browser.quit();
		} catch (Exception e) {
			System.err.println(e.getMessage());
		}
	}
}
