package fr.trxyy.alternative.alternative_api.updater;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import fr.trxyy.alternative.alternative_api.GameEngine;
import fr.trxyy.alternative.alternative_api.GameStyle;
import fr.trxyy.alternative.alternative_api.GameVerifier;
import fr.trxyy.alternative.alternative_api.account.Session;
import fr.trxyy.alternative.alternative_api.assets.AssetIndex;
import fr.trxyy.alternative.alternative_api.assets.AssetObject;
import fr.trxyy.alternative.alternative_api.build.GameRunner;
import fr.trxyy.alternative.alternative_api.minecraft.Arch;
import fr.trxyy.alternative.alternative_api.minecraft.CompatibilityRule;
import fr.trxyy.alternative.alternative_api.minecraft.MinecraftLibrary;
import fr.trxyy.alternative.alternative_api.minecraft.MinecraftVersion;
import fr.trxyy.alternative.alternative_api.utils.FileUtil;
import fr.trxyy.alternative.alternative_api.utils.GameUtils;
import fr.trxyy.alternative.alternative_api.utils.JsonUtil;
import fr.trxyy.alternative.alternative_api.utils.LauncherFile;
import fr.trxyy.alternative.alternative_api.utils.Logger;
import fr.trxyy.alternative.alternative_api_ui.LauncherProgressBar;

public class GameUpdater extends Thread {

	public HashMap<String, LauncherFile> files = new HashMap<String, LauncherFile>();
	private static final String ASSETS_URL = "http://resources.download.minecraft.net/";
	public static MinecraftVersion minecraftVersion;
	public boolean hasCustomJar = false;
	public AssetIndex assetsList;
	public GameEngine engine;
	private Session session;
	private GameVerifier verifier;
	/** ASSETS POOL */
	private ExecutorService assetsExecutor = Executors.newFixedThreadPool(5);
	/** CUSTOM JARS POOL */
	private ExecutorService customJarsExecutor = Executors.newFixedThreadPool(5);
	/** JARS POOL */
	private ExecutorService jarsExecutor = Executors.newFixedThreadPool(5);
	/** PROGRESS BAR */
	public LauncherProgressBar fakeProgressBar;
	private String currentInfoText = "";
	private String currentFile = "";
	/** TRY FAKE PROGRESS */
	public int downloadedFiles = 0; // Downloader
	public int filesToDownload = 0;

	public void reg(GameEngine gameEngine) {
		this.engine = gameEngine;
	}
	
	public void reg(LauncherProgressBar suppBar) {
	    if (suppBar != null) {
	        fakeProgressBar = suppBar;
	      } else {
	        fakeProgressBar = new LauncherProgressBar();
	      } 
	}

	public void reg(Session account) {
		this.session = account;
	}

	@Override
	public void run() {
		/** --------------------------------------  */
		Logger.log("\n\n");
		Logger.log("=============UPDATING GAME==============");
		
		this.setCurrentInfoText("Preparation de la mise a jour.");
		
		this.verifier = new GameVerifier(this.engine);
		Logger.log("Getting ignore/delete list   [Extra Step]");
		Logger.log("========================================");
		
		this.setCurrentInfoText("Recuperation de la ignoreList/deleteList.");
		
		this.verifier.getIgnoreList();
		this.verifier.getDeleteList();
		Logger.log("Indexing version              [Step 1/5]");
		Logger.log("========================================");
		
		this.setCurrentInfoText("Acquisition de la version Minecraft.");
		
		this.indexVersion();
		Logger.log("Indexing assets               [Step 2/5]");
		Logger.log("========================================");
		
		this.setCurrentInfoText("Acquisition des fichiers de ressources.");
		
		this.indexAssets();
		if (!this.engine.getGameStyle().equals(GameStyle.VANILLA)) {
			Logger.log("Indexing custom jars        [Extra Step]");
			Logger.log("========================================");
			
			this.setCurrentInfoText("Acquisition des fichiers perso requis.");
			
			GameParser.getFilesToDownload(engine);
		}
		Logger.log("Updating assets               [Step 3/5]");
		Logger.log("========================================");
		
		this.setCurrentInfoText("Telechargement des ressources.");
		
		this.updateAssets();
		Logger.log("Updating jars/libraries       [Step 4/5]");
		Logger.log("========================================");
		
		this.setCurrentInfoText("Telechargement des librairies.");
		
		this.updateJars();
		if (!engine.getGameStyle().equals(GameStyle.VANILLA)) {
			Logger.log("Updating custom jars        [Extra Step]");
			Logger.log("========================================");
			
			this.setCurrentInfoText("Telechargement des ressources perso.");
			
			this.updateCustomJars();
		}
		this.customJarsExecutor.shutdown();
		try {
			this.customJarsExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		Logger.log("Cleaning installation         [Step 5/5]");
		Logger.log("========================================");
		
		this.setCurrentInfoText("Verification de l'installation.");
		
		this.verifier.verify();
		Logger.log("\n\n");
		Logger.log("========================================");
		Logger.log("|      Update Finished. Launching.     |");
		Logger.log("|            Version " + minecraftVersion.getId() + "            |");
		Logger.log("|          Runtime: " + System.getProperty("java.version") + "          |");
		Logger.log("|              Build ID: -1            |");
		Logger.log("========================================");
		Logger.log("\n\n");
		Logger.log("==============GAME OUTPUT===============");
		
		
		this.setCurrentInfoText("Telechargement accompli.");
		
		GameRunner forgeGame = new GameRunner(this.engine, this.session);
		try {
			Process p = forgeGame.launch();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static String constructClasspath(GameEngine engine) {
		Logger.log("Constructing classpath (new)");
		String result = "";
		String separator = System.getProperty("path.separator");
		for (MinecraftLibrary lib : minecraftVersion.getLibraries()) {
			File libPath = new File(engine.getGameFolder().getLibsDir(), lib.getArtifactPath());
			result += libPath + separator;
		}
		result += engine.getGameFolder().getGameJar().getAbsolutePath();
		return result;
	}
	
	@SuppressWarnings("unlikely-arg-type")
	public void updateJars() {
		for (MinecraftLibrary lib : minecraftVersion.getLibraries()) {
			File libPath = new File(engine.getGameFolder().getLibsDir(), lib.getArtifactPath());
			
			GameVerifier.addToFileList(libPath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), "").replace('/', File.separatorChar));
			
			if (lib.getCompatibilityRules() != null) {
				for (final CompatibilityRule rule : lib.getCompatibilityRules()) {
					if (rule.getOs() != null && rule.getAction() != null) {
						for (final String os : rule.getOs().getName().getAliases()) {
							if (lib.appliesToCurrentEnvironment()) {
								if (rule.getAction().equals("disallow")) {
									lib.setSkipped(true);
								} else {
									lib.setSkipped(false);
								}
							} else {
								if (rule.getAction().equals("allow")) {
									lib.setSkipped(false);
								} else {
									lib.setSkipped(true);
								}
							}
						}
					}
				}
			}

			if (!lib.isSkipped()) {
				if (lib.getDownloads().getArtifact() != null) {
					final Downloader downloadTask = new Downloader(libPath, lib.getDownloads().getArtifact().getUrl().toString(), lib.getDownloads().getArtifact().getSha1(), engine);
					if (downloadTask.requireUpdate()) {
						if (!verifier.existInDeleteList(libPath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""))) {
							this.filesToDownload++;
							this.jarsExecutor.submit(downloadTask);
						}
						else {
							Logger.err("SKIPPED >> " + libPath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""));
						}
					}
				}

				if (lib.hasNatives()) {
					for (final String osName : lib.getNatives().values()) {
						String realOsName = osName.replace("${arch}", Arch.CURRENT.getBit());
						if (lib.getDownloads().getClassifiers().get(realOsName) != null) {
							final File nativePath = new File(engine.getGameFolder().getNativesCacheDir(), lib.getArtifactNatives(realOsName));
							GameVerifier.addToFileList(nativePath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), "").replace('/', File.separatorChar));
							final Downloader downloadTask8 = new Downloader(nativePath,
									lib.getDownloads().getClassifiers().get(realOsName).getUrl().toString(),
									lib.getDownloads().getClassifiers().get(realOsName).getSha1(), engine);
							if (downloadTask8.requireUpdate()) {
								if (!verifier.existInDeleteList(nativePath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""))) {
									this.filesToDownload++;
									this.jarsExecutor.submit(downloadTask8);
								}
								else {
									Logger.err("SKIPPED >> " + nativePath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""));
								}
							}
						}
					}
				}
			}
		}
		final Downloader downloadTask3 = new Downloader(new File(engine.getGameFolder().getBinDir(), "minecraft.jar"),
				minecraftVersion.getDownloads().getClient().getUrl().toString(),
				minecraftVersion.getDownloads().getClient().getSha1(), engine);
		GameVerifier.addToFileList(new File(engine.getGameFolder().getBinDir(), "minecraft.jar").getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), "").replace('/', File.separatorChar));
		
		if (downloadTask3.requireUpdate()) {
			if (!this.hasCustomJar) {
				this.jarsExecutor.submit(downloadTask3);
				this.filesToDownload++;
			}
			/**
			 * On annule le telechargement du client si on doit telecharger un client custom
			 */
		}
		this.jarsExecutor.shutdown();

		try {
			this.jarsExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public void updateAssets() {
		String json = null;
		String assetUrl = minecraftVersion.getAssetIndex().getUrl().toString();
		AssetIndex assetsList;
		try {
			json = JsonUtil.loadJSON(assetUrl);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			assetsList = (AssetIndex) JsonUtil.getGson().fromJson(json, AssetIndex.class);
		}
		Map<String, AssetObject> objects = assetsList.getObjects();
		for (String assetKey : objects.keySet()) {
			AssetObject asset = (AssetObject) objects.get(assetKey);
			File mc = getAssetInMcFolder(asset.getHash());
			File local = getAsset(asset.getHash());
			
			GameVerifier.addToFileList(local.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), "").replace('/', File.separatorChar));
			
			local.getParentFile().mkdirs();
			if ((!local.exists()) || (!FileUtil.matchSHA1(local, asset.getHash()))) {
				if ((!local.exists()) && (mc.exists()) && (FileUtil.matchSHA1(mc, asset.getHash()))) {
					this.assetsExecutor.submit(new Duplicator(mc, local));
					Logger.log("Copying asset " + local.getName());
					this.setCurrentInfoText("Copie d'une ressource.");
				} else {
					Downloader downloadTask = new Downloader(local, toURL(asset.getHash()), asset.getHash(), engine);
					if (downloadTask.requireUpdate()) {
						this.assetsExecutor.submit(downloadTask);
						this.filesToDownload++;
						Logger.log("Downloading asset " + local.getName());
					}
				}
			}
		}
		this.assetsExecutor.shutdown();
		File indexes = new File(engine.getGameFolder().getAssetsDir(), "indexes");
		indexes.mkdirs();
		File index = new File(indexes, minecraftVersion.getAssets() + ".json");
		
		GameVerifier.addToFileList(index.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), "").replace('/', File.separatorChar));
		
		if (!index.exists()) {
			try {
				index.createNewFile();
				BufferedWriter writer = new BufferedWriter(new FileWriter(index));
				writer.write(JsonUtil.getGson().toJson(assetsList));
				writer.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		try {
			this.assetsExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	public void indexVersion() {
		String json = null;
		try {
			json = JsonUtil.loadJSON(engine.getGameLinks().getJsonUrl());
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			minecraftVersion = (MinecraftVersion) JsonUtil.getGson().fromJson(json, MinecraftVersion.class);
		}
	}

	public void indexAssets() {
		String json = null;
		String assetUrl = minecraftVersion.getAssetIndex().getUrl().toString();
		try {
			json = JsonUtil.loadJSON(assetUrl);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			assetsList = (AssetIndex) JsonUtil.getGson().fromJson(json, AssetIndex.class);
		}
	}

	public AssetIndex getAssetsList() {
		return assetsList;
	}

	private String toURL(String hash) {
		return ASSETS_URL + hash.substring(0, 2) + "/" + hash;
	}
	
	private void updateCustomJars() {
		for (String name : this.files.keySet()) {
			String fileDest = name.replace(engine.getGameLinks().getCustomFilesUrl(), "");
			String fileName = fileDest;
			int index = fileName.lastIndexOf("\\");
			String dirLocation = fileName.substring(index + 1);
			
			File libPath = new File(engine.getGameFolder().getGameDir() + File.separator + dirLocation);
			String url = engine.getGameLinks().getCustomFilesUrl() + name;
			
			final Downloader customDownloadTask = new Downloader(libPath, url, null, engine); // GameParser
				if (!verifier.existInDeleteList(libPath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""))) {
					this.customJarsExecutor.submit(customDownloadTask);
				}
				else {
					Logger.err("SKIPPED >> " + libPath.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""));
				}
			
			
//			if (!name.endsWith("/")) {
//				String url = engine.getGameLinks().getCustomFilesUrl() + name;
//				File file = new File(engine.getGameFolder().getGameDir() + File.separator + dirLocation);
//				if (!verifier.existInDeleteList(file.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""))) {
//					downloadFile(url, file);
//				}
//				else {
//					Logger.err("SKIPPED >> " + file.getAbsolutePath().replace(engine.getGameFolder().getGameDir().getAbsolutePath(), ""));
//				}
//			}
		}
	}
	
	private File getAsset(String hash) {
		File assetsDir = this.engine.getGameFolder().getAssetsDir();
		File mcObjectsDir = new File(assetsDir, "objects");
		File hex = new File(mcObjectsDir, hash.substring(0, 2));
		return new File(hex, hash);
	}

	private File getAssetInMcFolder(String hash) {
		File minecraftAssetsDir = new File(GameUtils.getWorkingDirectory("minecraft"), "assets");
		File minecraftObjectsDir = new File(minecraftAssetsDir, "objects");
		File hex = new File(minecraftObjectsDir, hash.substring(0, 2));
		return new File(hex, hash);
	}

	public GameEngine getEngine() {
		return engine;
	}
	
	public LauncherProgressBar getProgressBar() {
		return fakeProgressBar;
	}

	public String getCurrentInfo() {
		return this.currentInfoText;
	}

	public void setCurrentInfoText(String name) {
		this.currentInfoText = name;
	}
	
	public String getCurrentFile() {
		return this.currentFile;
	}

	public void setCurrentFille(String name) {
		this.currentFile = name;
	}
}
