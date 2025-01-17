////////////////////////////////////////////////////////////////////////////////
//
//  Licensed to the Apache Software Foundation (ASF) under one or more
//  contributor license agreements.  See the NOTICE file distributed with
//  this work for additional information regarding copyright ownership.
//  The ASF licenses this file to You under the Apache License, Version 2.0
//  (the "License"); you may not use this file except in compliance with
//  the License.  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

package org.apache.royale.formatter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.royale.compiler.clients.problems.CompilerProblemCategorizer;
import org.apache.royale.compiler.clients.problems.ProblemFormatter;
import org.apache.royale.compiler.clients.problems.ProblemPrinter;
import org.apache.royale.compiler.clients.problems.ProblemQuery;
import org.apache.royale.compiler.clients.problems.WorkspaceProblemFormatter;
import org.apache.royale.compiler.common.VersionInfo;
import org.apache.royale.compiler.exceptions.ConfigurationException;
import org.apache.royale.compiler.filespecs.FileSpecification;
import org.apache.royale.compiler.internal.config.localization.LocalizationManager;
import org.apache.royale.compiler.internal.workspaces.Workspace;
import org.apache.royale.compiler.problems.ConfigurationProblem;
import org.apache.royale.compiler.problems.ICompilerProblem;
import org.apache.royale.compiler.problems.UnexpectedExceptionProblem;
import org.apache.royale.formatter.config.CommandLineConfigurator;
import org.apache.royale.formatter.config.Configuration;
import org.apache.royale.formatter.config.ConfigurationBuffer;
import org.apache.royale.formatter.config.ConfigurationValue;
import org.apache.royale.formatter.config.Configurator;
import org.apache.royale.formatter.config.Semicolons;
import org.apache.royale.utils.FilenameNormalization;

/**
 * Formats .as and .mxml source files.
 */
public class FORMATTER {
	private static final String NEWLINE = System.getProperty("line.separator");
	private static final String DEFAULT_VAR = "files";
	private static final String L10N_CONFIG_PREFIX = "org.apache.royale.compiler.internal.config.configuration";

	static enum ExitCode {
		SUCCESS(0), PRINT_HELP(1), FAILED_WITH_ERRORS(2), FAILED_WITH_EXCEPTIONS(3), FAILED_WITH_CONFIG_PROBLEMS(4);

		ExitCode(int code) {
			this.code = code;
		}

		final int code;

		int getCode() {
			return code;
		}
	}

	/**
	 * Java program entry point.
	 * 
	 * @param args command line arguments
	 */
	public static void main(final String[] args) {
		FORMATTER formatter = new FORMATTER();
		int exitCode = formatter.execute(args);
		System.exit(exitCode);
	}

	public FORMATTER() {

	}

	public int tabSize = 4;
	public boolean insertSpaces = false;
	public boolean insertFinalNewLine = false;
	public boolean placeOpenBraceOnNewLine = true;
	public boolean insertSpaceAfterSemicolonInForStatements = true;
	public boolean insertSpaceAfterKeywordsInControlFlowStatements = true;
	public boolean insertSpaceAfterFunctionKeywordForAnonymousFunctions = false;
	public boolean insertSpaceBeforeAndAfterBinaryOperators = true;
	public boolean insertSpaceAfterCommaDelimiter = true;
	public boolean insertSpaceBetweenMetadataAttributes = true;
	public boolean insertSpaceAtStartOfLineComment = true;
	public int maxPreserveNewLines = 2;
	public Semicolons semicolons = Semicolons.INSERT;
	public boolean ignoreProblems = false;
	public boolean collapseEmptyBlocks = false;
	public boolean mxmlAlignAttributes = false;
	public boolean mxmlInsertNewLineBetweenAttributes = false;

	private ProblemQuery problemQuery;
	private List<File> inputFiles = new ArrayList<File>();
	private boolean writeBackToInputFiles = false;
	private boolean listChangedFiles = false;
	private Configuration configuration;
	private ConfigurationBuffer configBuffer;

	public int execute(String[] args) {
		ExitCode exitCode = ExitCode.SUCCESS;
		problemQuery = new ProblemQuery();
		problemQuery.setShowWarnings(false);

		try {
			boolean continueFormatting = configure(args, problemQuery);
			if (continueFormatting) {
				if (inputFiles.size() == 0) {
					StringBuilder builder = new StringBuilder();
					Scanner sysInScanner = new Scanner(System.in);
					try {
						while (sysInScanner.hasNext()) {
							builder.append(sysInScanner.next());
						}
					} finally {
						IOUtils.closeQuietly(sysInScanner);
					}
					String filePath = FilenameNormalization.normalize("stdin.as");
					String fileText = builder.toString();
					String formattedText = formatFileText(filePath, fileText, problemQuery.getProblems());
					if (!fileText.equals(formattedText)) {
						if (listChangedFiles) {
							System.out.println(filePath);
						}
					}
					if (!listChangedFiles) {
						System.out.println(formattedText);
					}
				} else {
					for (File inputFile : inputFiles) {
						String filePath = FilenameNormalization.normalize(inputFile.getAbsolutePath());
						FileSpecification fileSpec = new FileSpecification(filePath);
						String fileText = IOUtils.toString(fileSpec.createReader());
						String formattedText = formatFileText(filePath, fileText, problemQuery.getProblems());
						if (!fileText.equals(formattedText)) {
							if (listChangedFiles) {
								System.out.println(filePath);
							}
							if (writeBackToInputFiles) {
								FileUtils.write(inputFile, formattedText, "utf8");
							}
						}
						if (!listChangedFiles && !writeBackToInputFiles) {
							System.out.println(formattedText);
						}
					}
				}
			} else if (problemQuery.hasFilteredProblems()) {
				exitCode = ExitCode.FAILED_WITH_CONFIG_PROBLEMS;
			} else {
				exitCode = ExitCode.PRINT_HELP;
			}
		} catch (Exception e) {
			problemQuery.add(new UnexpectedExceptionProblem(e));
			System.err.println(e.getMessage());
			exitCode = ExitCode.FAILED_WITH_EXCEPTIONS;
		} finally {
			if (problemQuery.hasFilteredProblems()) {
				final Workspace workspace = new Workspace();
				final CompilerProblemCategorizer categorizer = new CompilerProblemCategorizer();
				final ProblemFormatter formatter = new WorkspaceProblemFormatter(workspace, categorizer);
				final ProblemPrinter printer = new ProblemPrinter(formatter);
				printer.printProblems(problemQuery.getFilteredProblems());
			}
		}
		return exitCode.getCode();
	}

	public String formatFile(File file, Collection<ICompilerProblem> problems) throws IOException {
		String filePath = FilenameNormalization.normalize(file.getAbsolutePath());
		FileSpecification fileSpec = new FileSpecification(filePath);
		String fileText = IOUtils.toString(fileSpec.createReader());
		return formatFileText(filePath, fileText, problems);
	}

	public String formatFile(File file) throws IOException {
		return formatFile(file, null);
	}

	public String formatFileText(String filePath, String text, Collection<ICompilerProblem> problems) {
		filePath = FilenameNormalization.normalize(filePath);
		String result = null;
		if (filePath.endsWith(".mxml")) {
			result = formatMXMLTokens(filePath, text, problems);
		} else {
			result = formatASTokens(filePath, text, problems);
		}
		if (insertFinalNewLine && result.charAt(result.length() - 1) != '\n') {
			return result + '\n';
		}
		return result;
	}

	public String formatFileText(String filePath, String text) {
		return formatFileText(filePath, text, null);
	}

	public String formatActionScriptText(String text, Collection<ICompilerProblem> problems) {
		String filePath = FilenameNormalization.normalize("stdin.as");
		return formatASTokens(filePath, text, problems);
	}

	public String formatActionScriptText(String text) {
		return formatActionScriptText(text, null);
	}

	public String formatMXMLText(String text, Collection<ICompilerProblem> problems) {
		String filePath = FilenameNormalization.normalize("stdin.mxml");
		return formatMXMLTokens(filePath, text, problems);
	}

	public String formatMXMLText(String text) {
		return formatMXMLText(text, null);
	}

	private String formatASTokens(String filePath, String text, Collection<ICompilerProblem> problems) {
		ASTokenFormatter asFormatter = new ASTokenFormatter(getFormatterSettings());
		return asFormatter.format(filePath, text, problems);
	}

	private String formatMXMLTokens(String filePath, String text, Collection<ICompilerProblem> problems) {
		MXMLTokenFormatter mxmlFormatter = new MXMLTokenFormatter(getFormatterSettings());
		return mxmlFormatter.format(filePath, text, problems);
	}

	private FormatterSettings getFormatterSettings() {
		FormatterSettings result = new FormatterSettings();
		result.tabSize = tabSize;
		result.insertSpaces = insertSpaces;
		result.insertFinalNewLine = insertFinalNewLine;
		result.placeOpenBraceOnNewLine = placeOpenBraceOnNewLine;
		result.insertSpaceAfterSemicolonInForStatements = insertSpaceAfterSemicolonInForStatements;
		result.insertSpaceAfterKeywordsInControlFlowStatements = insertSpaceAfterKeywordsInControlFlowStatements;
		result.insertSpaceAfterFunctionKeywordForAnonymousFunctions = insertSpaceAfterFunctionKeywordForAnonymousFunctions;
		result.insertSpaceBeforeAndAfterBinaryOperators = insertSpaceBeforeAndAfterBinaryOperators;
		result.insertSpaceAfterCommaDelimiter = insertSpaceAfterCommaDelimiter;
		result.insertSpaceBetweenMetadataAttributes = insertSpaceBetweenMetadataAttributes;
		result.insertSpaceAtStartOfLineComment = insertSpaceAtStartOfLineComment;
		result.maxPreserveNewLines = maxPreserveNewLines;
		result.semicolons = semicolons;
		result.ignoreProblems = ignoreProblems;
		result.collapseEmptyBlocks = collapseEmptyBlocks;
		result.mxmlAlignAttributes = mxmlAlignAttributes;
		result.mxmlInsertNewLineBetweenAttributes = mxmlInsertNewLineBetweenAttributes;
		return result;
	}

	/**
	 * Get the start up message that contains the program name with the copyright
	 * notice.
	 * 
	 * @return The startup message.
	 */
	protected String getStartMessage() {
		// This message should not be localized.
		String message = "Apache Royale ActionScript Formatter (asformat)" + NEWLINE + VersionInfo.buildMessage()
				+ NEWLINE;
		return message;
	}

	/**
	 * Get my program name.
	 * 
	 * @return always "asformat".
	 */
	protected String getProgramName() {
		return "asformat";
	}

	/**
	 * Print detailed help information if -help is provided.
	 */
	private void processHelp(final List<ConfigurationValue> helpVar) {
		final Set<String> keywords = new LinkedHashSet<String>();
		if (helpVar != null) {
			for (final ConfigurationValue val : helpVar) {
				for (final Object element : val.getArgs()) {
					String keyword = (String) element;
					while (keyword.startsWith("-"))
						keyword = keyword.substring(1);
					keywords.add(keyword);
				}
			}
		}

		if (keywords.size() == 0)
			keywords.add("help");

		final String usages = CommandLineConfigurator.usage(getProgramName(), DEFAULT_VAR, configBuffer, keywords,
				LocalizationManager.get(), L10N_CONFIG_PREFIX);
		System.out.println(getStartMessage());
		System.out.println(usages);
	}

	private boolean configure(String[] args, ProblemQuery problems) {
		try {
			Configurator configurator = new Configurator();
			configurator.setConfiguration(args, "files");
			configuration = configurator.getConfiguration();
			configBuffer = configurator.getConfigurationBuffer();

			problems.addAll(configurator.getConfigurationProblems());

			if (configBuffer.getVar("version") != null) {
				System.out.println(VersionInfo.buildMessage());
				return false;
			}

			// Print help if "-help" is present.
			final List<ConfigurationValue> helpVar = configBuffer.getVar("help");
			if (helpVar != null || args.length == 0) {
				processHelp(helpVar);
				return false;
			}

			if (problems.hasErrors()) {
				return false;
			}

			collapseEmptyBlocks = configuration.getCollapseEmptyBlocks();
			ignoreProblems = configuration.getIgnoreParsingProblems();
			insertFinalNewLine = configuration.getInsertFinalNewLine();
			insertSpaceAfterCommaDelimiter = configuration.getInsertSpaceAfterCommaDelimiter();
			insertSpaceBetweenMetadataAttributes = configuration.getInsertSpaceBetweenMetadataAttributes();
			insertSpaceAfterFunctionKeywordForAnonymousFunctions = configuration
					.getInsertSpaceAfterFunctionKeywordForAnonymousFunctions();
			insertSpaceAfterKeywordsInControlFlowStatements = configuration
					.getInsertSpaceAfterKeywordsInControlFlowStatements();
			insertSpaceAfterSemicolonInForStatements = configuration.getInsertSpaceAfterSemicolonInForStatements();
			insertSpaceBeforeAndAfterBinaryOperators = configuration.getInsertSpaceBeforeAndAfterBinaryOperators();
			insertSpaceAtStartOfLineComment = configuration.getInsertSpaceAtStartOfLineComment();
			insertSpaces = configuration.getInsertSpaces();
			mxmlInsertNewLineBetweenAttributes = configuration.getMxmlInsertNewLineBetweenAttributes();
			mxmlAlignAttributes = configuration.getMxmlAlignAttributes();
			listChangedFiles = configuration.getListFiles();
			maxPreserveNewLines = configuration.getMaxPreserveNewLines();
			placeOpenBraceOnNewLine = configuration.getPlaceOpenBraceOnNewLine();
			semicolons = Semicolons.valueOf(configuration.getSemicolons().toUpperCase());
			tabSize = configuration.getTabSize();
			writeBackToInputFiles = configuration.getWriteFiles();
			for (String filePath : configuration.getFiles()) {
				File inputFile = new File(filePath);
				if (!inputFile.exists()) {
					throw new ConfigurationException("Input file does not exist: " + filePath, null, -1);
				}
				if (inputFile.isDirectory()) {
					addDirectory(inputFile);
				} else {
					inputFiles.add(inputFile);
				}
			}
			if (inputFiles.size() == 0 && listChangedFiles) {
				throw new ConfigurationException("Cannot use -list-files with standard input", null, -1);
			}
			if (writeBackToInputFiles) {
				if (inputFiles.size() == 0) {
					throw new ConfigurationException("Cannot use -write-files with standard input", null, -1);
				}
				for (File inputFile : inputFiles) {
					if (!inputFile.canWrite()) {
						throw new ConfigurationException("File is read-only: " + inputFile.getPath(), null, -1);
					}
				}
			}
			return true;
		} catch (ConfigurationException e) {
			final ICompilerProblem problem = new ConfigurationProblem(e);
			problems.add(problem);
			return false;
		} catch (Exception e) {
			final ICompilerProblem problem = new ConfigurationProblem(null, -1, -1, -1, -1, e.getMessage());
			problems.add(problem);
			return false;
		}
	}

	private void addDirectory(File inputFile) {
		for (File file : inputFile.listFiles()) {
			String fileName = file.getName();
			if (fileName.startsWith(".")) {
				continue;
			}
			if (file.isDirectory()) {
				addDirectory(file);
			} else if (fileName.endsWith(".as") || fileName.endsWith(".mxml")) {
				inputFiles.add(file);
			}
		}
	}
}